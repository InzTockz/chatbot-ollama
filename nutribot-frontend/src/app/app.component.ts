import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ChatService, KnowledgeDocument, PendingQuery } from './services/chat.service';

interface Message {
  role: 'user' | 'bot';
  text: string;
  derivar?: boolean;     // muestra el boton de WhatsApp (hay asesor disponible)
  pedirDatos?: boolean;  // muestra el formulario de bitacora (no hay asesor) - HU09
}

@Component({
  selector: 'app-root',
  imports: [CommonModule, FormsModule],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent {

  tab: 'chat' | 'admin' | 'bitacora' = 'chat';

  // ----- Chat (HU01) -----
  messages: Message[] = [];
  input = '';
  loading = false;
  esperandoConfirmacion = false;   // el bot pregunto si desea un asesor (HU08)
  consultaParaAsesor = '';         // consulta original (contexto para el asesor)
  // HU05: id unico de esta conversacion; el backend lo usa para recordar el contexto de la sesion
  private readonly conversationId = crypto.randomUUID();

  // ----- Bitacora (HU09) -----
  bitacoraNombre = '';
  bitacoraTelefono = '';

  // ----- Admin (HU06) -----
  documents: KnowledgeDocument[] = [];
  selectedFile: File | null = null;
  uploading = false;
  uploadMsg = '';

  // ----- Bitacora del asesor (HU10) -----
  pendientes: PendingQuery[] = [];
  filtroEstado = 'TODOS';
  filtroFecha = '';

  constructor(private chatService: ChatService) {}

  switchTab(t: 'chat' | 'admin' | 'bitacora'): void {
    this.tab = t;
    if (t === 'admin') {
      this.loadDocuments();
    }
    if (t === 'bitacora') {
      this.loadPendientes();
    }
  }

  send(): void {
    const text = this.input.trim();
    if (!text || this.loading) {
      return;
    }
    // HU05: guardamos la pregunta anterior para contextualizar los seguimientos
    const previousQuestion = this.messages.filter((m) => m.role === 'user').pop()?.text ?? '';
    this.messages.push({ role: 'user', text });
    this.input = '';

    // Si el bot habia preguntado si desea un asesor, interpretamos la respuesta (HU08)
    if (this.esperandoConfirmacion) {
      this.esperandoConfirmacion = false;
      if (this.esAfirmacion(text)) {
        const msg: Message = {
          role: 'bot',
          text: this.asesorDisponible()
            ? 'Perfecto, te derivo con un asesor.'
            : 'En este momento no hay asesores disponibles. Dejanos tus datos y te contactaremos.'
        };
        this.marcarDerivacion(msg);
        this.messages.push(msg);
        return;
      }
      if (this.esNegacion(text)) {
        this.messages.push({ role: 'bot', text: 'De acuerdo, seguimos por aqui. ¿En que mas puedo ayudarte?' });
        return;
      }
      // Si no es afirmacion ni negacion, se trata como una nueva consulta (continua abajo)
    }

    this.loading = true;
    this.chatService.ask(text, this.conversationId, previousQuestion).subscribe({
      next: (res) => {
        const msg: Message = { role: 'bot', text: res.answer };
        if (res.accion === 'BOTON') {
          this.marcarDerivacion(msg);
        }
        this.messages.push(msg);
        if (res.accion === 'PREGUNTAR') {
          this.esperandoConfirmacion = true;
        }
        if (res.accion === 'BOTON' || res.accion === 'PREGUNTAR') {
          this.consultaParaAsesor = text;
        }
        this.loading = false;
      },
      error: () => {
        this.messages.push({
          role: 'bot',
          text: 'Ocurrio un error al consultar. Verifica que el backend este corriendo.'
        });
        this.loading = false;
      }
    });
  }

  // Decide como ofrecer el asesor: WhatsApp si hay asesor disponible; formulario de bitacora si no (HU09)
  private marcarDerivacion(msg: Message): void {
    if (this.asesorDisponible()) {
      msg.derivar = true;
    } else {
      msg.pedirDatos = true;
    }
  }

  // Horario de atencion en la zona horaria de Peru (America/Lima), sin importar el
  // dispositivo del cliente: Lun-Vie 8:00-18:00, Sab 8:00-13:00.
  private asesorDisponible(): boolean {
    const partes = new Intl.DateTimeFormat('en-US', {
      timeZone: 'America/Lima',
      weekday: 'short',
      hour: '2-digit',
      hourCycle: 'h23'
    }).formatToParts(new Date());
    const dia = partes.find((p) => p.type === 'weekday')?.value ?? '';
    const hora = parseInt(partes.find((p) => p.type === 'hour')?.value ?? '0', 10);

    if (['Mon', 'Tue', 'Wed', 'Thu', 'Fri'].includes(dia)) {
      return hora >= 8 && hora < 18;
    }
    if (dia === 'Sat') {
      return hora >= 8 && hora < 13;
    }
    return false;
  }

  // HU09: registrar los datos del cliente en la bitacora
  enviarBitacora(): void {
    const nombre = this.bitacoraNombre.trim();
    const telefono = this.bitacoraTelefono.trim();
    if (!nombre || !telefono) {
      return;
    }
    this.chatService.registrarBitacora(nombre, telefono, this.consultaParaAsesor).subscribe({
      next: () => {
        this.messages.forEach((m) => (m.pedirDatos = false));
        this.messages.push({
          role: 'bot',
          text: `Gracias ${nombre}. Tu consulta quedo registrada y un asesor te contactara al ${telefono}.`
        });
        this.bitacoraNombre = '';
        this.bitacoraTelefono = '';
      },
      error: () => {
        this.messages.push({ role: 'bot', text: 'No pude registrar tus datos. Intenta nuevamente.' });
      }
    });
  }

  // Reconoce respuestas afirmativas del cliente
  private esAfirmacion(text: string): boolean {
    const t = text.toLowerCase().replace(/[.,!¡¿?]/g, ' ').trim();
    const palabras = t.split(/\s+/);
    const simples = ['si', 'sí', 'claro', 'dale', 'ok', 'okay', 'bueno', 'afirmativo',
                     'correcto', 'quiero', 'deseo', 'acepto', 'listo', 'sip', 'sii', 'obvio'];
    if (palabras.some((p) => simples.includes(p))) {
      return true;
    }
    return ['por favor', 'esta bien', 'de una', 'me interesa'].some((f) => t.includes(f));
  }

  // Reconoce respuestas negativas del cliente
  private esNegacion(text: string): boolean {
    const t = text.toLowerCase().replace(/[.,!¡¿?]/g, ' ').trim();
    const palabras = t.split(/\s+/);
    const simples = ['no', 'nel', 'negativo', 'paso', 'nop'];
    if (palabras.some((p) => simples.includes(p))) {
      return true;
    }
    return ['ahorita no', 'asi estoy bien', 'asi esta bien', 'despues', 'luego'].some((f) => t.includes(f));
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile = input.files && input.files.length ? input.files[0] : null;
    this.uploadMsg = '';
  }

  upload(): void {
    if (!this.selectedFile || this.uploading) {
      return;
    }
    this.uploading = true;
    this.uploadMsg = '';

    this.chatService.uploadDocument(this.selectedFile).subscribe({
      next: (doc) => {
        this.uploadMsg = `"${doc.fileName}" indexado en ${doc.chunkCount} fragmento(s).`;
        this.uploading = false;
        this.selectedFile = null;
        this.loadDocuments();
      },
      error: (err) => {
        this.uploadMsg = err?.error?.message || 'Error al subir el documento.';
        this.uploading = false;
      }
    });
  }

  loadDocuments(): void {
    this.chatService.listDocuments().subscribe({
      next: (docs) => (this.documents = docs),
      error: () => {}
    });
  }

  // HU10: cargar la bitacora de consultas pendientes
  loadPendientes(): void {
    this.chatService.listPendingQueries().subscribe({
      next: (lista) => (this.pendientes = lista),
      error: () => (this.pendientes = [])
    });
  }

  // HU10: filtrado por estado y por fecha
  get pendientesFiltrados(): PendingQuery[] {
    return this.pendientes.filter((p) => {
      const okEstado = this.filtroEstado === 'TODOS' || p.estado === this.filtroEstado;
      const okFecha = !this.filtroFecha || (p.createdAt ?? '').startsWith(this.filtroFecha);
      return okEstado && okFecha;
    });
  }

  deleteDoc(id: number): void {
    if (!confirm('Eliminar este documento de la base de conocimiento?')) {
      return;
    }
    this.chatService.deleteDocument(id).subscribe({
      next: () => {
        this.uploadMsg = 'Documento eliminado.';
        this.loadDocuments();
      },
      error: () => {
        this.uploadMsg = 'No se pudo eliminar el documento.';
      }
    });
  }

  // HU07: derivar a un asesor humano por WhatsApp (con la consulta original como contexto)
  talkToAdvisor(): void {
    const consulta =
      this.consultaParaAsesor ||
      this.messages.filter((m) => m.role === 'user').pop()?.text ||
      '';
    const msg = consulta
      ? `Hola, vengo del chat de NutriBot. Mi consulta: ${consulta}`
      : 'Hola, vengo del chat de NutriBot y me gustaria hablar con un asesor.';

    this.chatService.getAdvisorWhatsapp(msg).subscribe({
      next: (res) => {
        this.messages.push({
          role: 'bot',
          text: 'Te estoy derivando con un asesor. Te atendera una persona por WhatsApp.'
        });
        window.open(res.url, '_blank');
      },
      error: () => {
        this.messages.push({
          role: 'bot',
          text: 'No pude abrir WhatsApp en este momento. Intenta nuevamente.'
        });
      }
    });
  }
}
