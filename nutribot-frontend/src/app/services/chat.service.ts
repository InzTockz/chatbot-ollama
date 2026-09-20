import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface ChatResponse {
  answer: string;
  accion: string; // 'NINGUNA' | 'BOTON' | 'PREGUNTAR'
}

/** HU09/HU10: consulta pendiente registrada en la bitacora */
export interface PendingQuery {
  id: number;
  nombre: string;
  telefono: string;
  consulta: string;
  estado: string;
  createdAt: string;
}

export interface KnowledgeDocument {
  id: number;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  chunkCount: number;
  status: string;
  createdAt: string;
}

@Injectable({ providedIn: 'root' })
export class ChatService {

  // URL del backend Spring Boot
  private readonly apiUrl = 'http://localhost:8080';

  constructor(private http: HttpClient) {}

  /** HU01/HU05: enviar una consulta al chat (id de conversacion + pregunta anterior para contextualizar) */
  ask(message: string, conversationId: string, previousQuestion: string): Observable<ChatResponse> {
    return this.http.post<ChatResponse>(`${this.apiUrl}/api/chat`, {
      message,
      conversationId,
      previousQuestion
    });
  }

  /** HU06: subir un documento para indexarlo */
  uploadDocument(file: File): Observable<KnowledgeDocument> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<KnowledgeDocument>(`${this.apiUrl}/api/admin/documents`, form);
  }

  /** HU06: listar documentos cargados */
  listDocuments(): Observable<KnowledgeDocument[]> {
    return this.http.get<KnowledgeDocument[]>(`${this.apiUrl}/api/admin/documents`);
  }

  /** HU06: eliminar un documento por id */
  deleteDocument(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/api/admin/documents/${id}`);
  }

  /** HU07: obtener el enlace de WhatsApp del asesor (con mensaje prellenado) */
  getAdvisorWhatsapp(message?: string): Observable<{ phone: string; url: string }> {
    const params = message ? `?message=${encodeURIComponent(message)}` : '';
    return this.http.get<{ phone: string; url: string }>(
      `${this.apiUrl}/api/advisor/whatsapp${params}`
    );
  }

  /** HU09: registrar una consulta pendiente en la bitacora (cuando no hay asesor) */
  registrarBitacora(nombre: string, telefono: string, consulta: string): Observable<unknown> {
    return this.http.post(`${this.apiUrl}/api/pending-queries`, { nombre, telefono, consulta });
  }

  /** HU10: listar las consultas pendientes de la bitacora (vista del asesor) */
  listPendingQueries(): Observable<PendingQuery[]> {
    return this.http.get<PendingQuery[]>(`${this.apiUrl}/api/pending-queries`);
  }
}
