package com.battilana.nutribot.service;

import com.battilana.nutribot.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Logica del chat (HU01) con memoria de conversacion (HU05) y derivacion a un asesor.
 * El asesor se ofrece SOLO cuando:
 *   (a) el cliente lo pide explicitamente (HU07), o
 *   (b) no hay informacion suficiente en la base de conocimiento -> baja certeza (HU08).
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient nutribotChatClient;
    private final ChatClient nutribotReformulacionChatClient;
    private final ChatClient nutribotContextoChatClient;
    private final VectorStore vectorStore;

    @Value("${nutribot.rag.similarity-threshold:0.4}")
    private double similarityThreshold;

    /**
     * DEF-10: nombre del documento que contiene la lista de precios oficial. En las consultas de
     * precio la busqueda se restringe a el por metadato "source", para que los fragmentos de la
     * ficha tecnica o del FAQ no ocupen los cupos de topK y desplacen al bloque del producto.
     */
    @Value("${nutribot.rag.price-document:lista-precios-battilana.txt}")
    private String priceDocument;

    // Intencion del cliente de hablar con una persona
    private static final List<String> PIDE_ASESOR = List.of(
            "asesor", "humano", "una persona", "vendedor", "agente", "hablar con alguien");

    // Consultas de precio/compra
    private static final List<String> ES_CONSULTA_PRECIO = List.of(
            "precio", "costo", "cuesta", "cuestan", "vale", "valen", "valor", "sale en",
            "cobran", "cobra", "pagar", "stock", "disponib", "comprar", "cotiz", "venden");

    // Senales de incertidumbre en la respuesta (precio variable / sin precio / sin stock / desconocido)
    private static final List<String> INCERTIDUMBRE = List.of(
            "variable", "consultar", "bajo pedido", "sin stock", "no hay stock", "agotado",
            "no especifica", "no tengo esa informacion", "no encontre", "no esta disponible");

    /**
     * HU05: seguimiento de tipo REFORMULACION. El cliente no pide informacion nueva, pide la
     * misma respuesta dicha de otro modo. Se resuelve solo con el historial, SIN RAG: recuperar
     * fragmentos aqui desviaba la respuesta hacia otro tema (DEF-07).
     */
    private static final List<String> MARCAS_REFORMULACION = List.of(
            "explicalo", "explicarlo", "explicame", "explica eso", "explicar eso",
            "no entendi", "no entiendo", "no comprendo", "mas simple", "mas facil",
            "mas claro", "en palabras simples", "repite", "repitelo", "repiteme",
            "resumelo", "resumeme", "en resumen", "de nuevo", "otra vez");

    // HU05: senales de que la pregunta depende de lo dicho antes (pregunta de seguimiento)
    private static final List<String> MARCAS_SEGUIMIENTO = List.of(
            "explicalo", "explicarlo", "explicame", "explica eso", "repite", "repitelo",
            "no entendi", "mas simple", "mas facil", "resumelo", "en resumen",
            "y eso", "de eso", "ese producto", "ese alimento", "ese insumo",
            "y cuanto", "y el precio", "y la ", "y su ");

    /**
     * HU05: ultima pregunta "de tema" de cada conversacion (la ultima que NO fue de seguimiento).
     * Es la referencia correcta para contextualizar: si usaramos simplemente la pregunta anterior,
     * dos seguimientos encadenados ("¿y cuanto cuesta?" -> "¿puedes explicarlo mas simple?")
     * harian que la busqueda pierda por completo el tema original.
     */
    private final Map<String, String> temaPorConversacion = new ConcurrentHashMap<>();

    /**
     * Palabras que se descartan al extraer el SUJETO de la pregunta anterior.
     * Incluye interrogativos, verbos y conectores, pero tambien los sustantivos de INTENCION
     * (dosis, precio, stock...): la intencion debe aportarla la pregunta actual, no la anterior.
     * Si no se descartaran, una consulta como "¿y cuanto cuesta?" contextualizada con
     * "¿que DOSIS necesita una gallina ponedora?" arrastraria la palabra "dosis" al embedding
     * y la busqueda devolveria la ficha tecnica en lugar de la lista de precios.
     */
    private static final Set<String> PALABRAS_VACIAS = Set.of(
            // interrogativos
            "que", "cual", "cuales", "cuanto", "cuanta", "cuantos", "cuantas", "como",
            "donde", "cuando", "quien", "quienes", "porque", "por",
            // verbos frecuentes
            "necesita", "necesitan", "tiene", "tienen", "es", "son", "esta", "estan", "hay",
            "debe", "deben", "puede", "pueden", "cuesta", "cuestan", "sirve", "sirven",
            "usa", "usan", "usar", "hace", "hacen", "ofrecen", "venden", "vende", "da", "dar",
            "almacena", "almacenan", "guarda", "guardan", "recomienda", "recomiendan",
            "vale", "valen", "valor", "cobra", "cobran", "pagar", "sale", "vender", "comprar",
            // articulos, preposiciones y pronombres
            "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "al", "a",
            "para", "con", "en", "y", "o", "su", "sus", "mi", "mis", "me", "se", "lo", "les",
            // sustantivos de intencion
            "dosis", "precio", "precios", "costo", "costos", "stock", "disponibilidad",
            "uso", "almacenamiento", "informacion", "ficha", "tecnica", "dato", "datos",
            "cantidad", "consumo");

    public ChatResponse responder(String message, String conversationId, String previousQuestion) {
        // HU05: el id de conversacion es OBLIGATORIO para la memoria; si no llega, usamos uno por defecto
        final String convId = (conversationId == null || conversationId.isBlank())
                ? "default" : conversationId;

        String texto = normalizar(message);

        // (a) El cliente pide explicitamente un asesor -> boton directo
        if (PIDE_ASESOR.stream().anyMatch(texto::contains)) {
            return new ChatResponse("Con gusto, puedes hablar con un asesor con el boton de abajo.", "BOTON");
        }

        // HU05 (b): reformulacion -> se responde SOLO con el historial, sin recuperar nada.
        // No actualiza el tema vigente: "explicalo mas simple" no cambia de que se esta hablando.
        if (esReformulacion(texto)) {
            String reformulada = nutribotReformulacionChatClient.prompt()
                    .user(message)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                    .call()
                    .content();
            return new ChatResponse(reformulada, "NINGUNA");
        }

        // HU05: si la pregunta es de seguimiento ("¿y cuanto cuesta?"), la contextualizamos con la
        // pregunta anterior. Asi la busqueda en Qdrant recupera los fragmentos del producto correcto
        // y se evita que el modelo mezcle informacion de otro producto.
        String consulta = message;
        String sujeto;
        if (esSeguimiento(texto)) {
            // Usamos la ultima pregunta DE TEMA de esta conversacion. Solo si no existe
            // (por ejemplo tras reiniciar el backend) caemos a la que envia el frontend.
            String tema = temaPorConversacion.get(convId);
            if (tema == null || tema.isBlank()) {
                tema = previousQuestion;
            }
            // Del tema anterior tomamos SOLO el sujeto (p. ej. "gallina ponedora"), no la
            // pregunta completa: asi la intencion la marca la pregunta actual y la busqueda
            // vectorial no se desvia hacia el tema de la consulta anterior.
            sujeto = extraerSujeto(tema);
            if (!sujeto.isBlank()) {
                // La pregunta principal va PRIMERO y el contexto al final, claramente marcado,
                // para que el modelo lo use solo para ubicar el tema y no responda de nuevo lo anterior.
                consulta = message + "\n[Contexto previo: " + sujeto + "]";
            }
        } else {
            // Pregunta nueva: pasa a ser el tema vigente de la conversacion.
            temaPorConversacion.put(convId, message);
            sujeto = extraerSujeto(message);
        }

        // (b) No hay informacion relevante -> boton directo
        List<Document> relevantes = vectorStore.similaritySearch(SearchRequest.builder()
                .query(consulta)
                .topK(6)
                .similarityThreshold(similarityThreshold)
                .build());
        if (relevantes == null || relevantes.isEmpty()) {
            return new ChatResponse(
                    "No encontre esa informacion en mi base de conocimiento. Si deseas, puedo derivarte con un asesor.", "BOTON");
        }

        boolean consultaPrecio = ES_CONSULTA_PRECIO.stream().anyMatch(texto::contains);

        String answer;
        if (consultaPrecio) {
            // DEF-10: primero acotamos la RECUPERACION a la lista de precios. Sin esto, una
            // consulta como "¿cuanto vale el alimento de gallinas?" llena los 6 cupos con
            // fragmentos de la ficha tecnica y del FAQ (ricos en la palabra "gallinas") y el
            // bloque de precios del producto ni siquiera llega: el filtro posterior solo podria
            // elegir el menos malo de un conjunto que ya venia equivocado.
            List<Document> deLaLista = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(consulta)
                    .topK(6)
                    .similarityThreshold(similarityThreshold)
                    .filterExpression(new FilterExpressionBuilder().eq("source", priceDocument).build())
                    .build());
            // Si la lista de precios no esta indexada con ese nombre, seguimos con lo general.
            List<Document> base = (deLaLista == null || deLaLista.isEmpty()) ? relevantes : deLaLista;

            // DEF-08: ademas, NO dejamos que el modelo elija entre varios productos. Filtramos el
            // contexto al bloque del producto consultado y se lo inyectamos ya acotado, de modo
            // que le resulte imposible atribuirle el precio de otro producto.
            String contexto = contextoDelProducto(base, sujeto);
            answer = nutribotContextoChatClient.prompt()
                    .user("Contexto:\n" + contexto + "\n\nPregunta del cliente: " + message)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                    .call()
                    .content();
        } else {
            // Respuesta RAG normal, recordando el historial de esta conversacion (HU05)
            answer = nutribotChatClient.prompt()
                    .user(consulta)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                    .call()
                    .content();
        }
        String low = (answer == null) ? "" : normalizar(answer);
        boolean incierto = INCERTIDUMBRE.stream().anyMatch(low::contains);

        if (consultaPrecio) {
            // Precio variable / sin precio / sin stock / desconocido -> boton directo.
            // Precio fijo + en stock -> preguntar si desea un asesor (boton solo si confirma).
            if (incierto) {
                return new ChatResponse(answer, "BOTON");
            }
            return new ChatResponse(
                    answer + "\n\n¿Deseas que te derive con un asesor para concretar tu compra?", "PREGUNTAR");
        }

        // Pregunta general: si el bot no tiene la info -> boton; si respondio bien -> nada
        return new ChatResponse(answer, incierto ? "BOTON" : "NINGUNA");
    }

    /**
     * Extrae el sujeto de una pregunta quitando signos, interrogativos, verbos, conectores y
     * sustantivos de intencion. Ejemplo:
     *   "¿Que dosis necesita una gallina ponedora?" -> "gallina ponedora"
     *   "¿Como se almacena el alimento balanceado?" -> "alimento balanceado"
     * Si no queda nada util, devuelve la pregunta original sin signos, para no perder el contexto.
     */
    private static String extraerSujeto(String pregunta) {
        if (pregunta == null || pregunta.isBlank()) {
            return "";
        }
        String limpia = normalizar(pregunta).replaceAll("[^a-z0-9ñ ]", " ").trim();
        String sujeto = java.util.Arrays.stream(limpia.split("\\s+"))
                .filter(p -> p.length() > 1)
                .filter(p -> !PALABRAS_VACIAS.contains(p))
                .collect(Collectors.joining(" "));
        return sujeto.isBlank() ? limpia : sujeto;
    }

    /**
     * DEF-08: construye el contexto de una consulta de precio conservando UNICAMENTE las lineas
     * que mencionan el producto consultado.
     *
     * La lista de precios repite el nombre del producto en cada linea ("Precio del alimento
     * balanceado para gallinas ponedoras: S/ 95.00"), por lo que el filtrado a nivel de linea es
     * preciso. Asi el modelo nunca ve los precios de los demas productos y le resulta imposible
     * cruzarlos. Si el filtro no deja ninguna linea, se devuelve el contexto completo para no
     * dejar al modelo sin informacion.
     */
    private String contextoDelProducto(List<Document> documentos, String sujeto) {
        String completo = documentos.stream()
                .map(Document::getText)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n"));

        if (sujeto == null || sujeto.isBlank()) {
            return completo;
        }

        List<String> raices = java.util.Arrays.stream(sujeto.split("\\s+"))
                .filter(p -> p.length() > 2)
                .map(ChatService::raiz)
                .toList();
        if (raices.isEmpty()) {
            return completo;
        }

        // Puntuamos cada BLOQUE (no cada linea) por cuantas raices del sujeto contiene y
        // conservamos los de puntaje MAXIMO. Dos decisiones importantes:
        //  - Bloque y no linea: el precio, la presentacion y la disponibilidad de un producto
        //    viven en lineas distintas. Filtrando por linea, "¿que precio tiene el SACO para
        //    ponedoras?" se quedaba solo con la linea de presentacion, que no trae el precio.
        //  - Puntaje maximo y no coincidencia total: el sujeto puede arrastrar alguna palabra
        //    que no exista en el catalogo (el verbo "vale"), y exigir que esten todas haria que
        //    una sola palabra desconocida anulara el filtro en silencio (DEF-09).
        List<String> bloques = separarEnBloques(completo);
        int maximo = bloques.stream().mapToInt(b -> coincidencias(b, raices)).max().orElse(0);
        if (maximo == 0) {
            return completo;   // el sujeto no aparece en el contexto: no hay nada que filtrar
        }

        return bloques.stream()
                .filter(b -> coincidencias(b, raices) == maximo)
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * Separa el contexto en bloques tematicos. Se corta ante una linea en blanco o al inicio de
     * un nuevo "PRODUCTO:", segun como haya quedado el fragmento despues del troceado.
     */
    private static List<String> separarEnBloques(String texto) {
        List<String> bloques = java.util.Arrays.stream(texto.split("(?m)\\n\\s*\\n|(?=^PRODUCTO:)"))
                .map(String::strip)
                .filter(b -> !b.isBlank())
                .toList();
        return bloques.isEmpty() ? List.of(texto) : bloques;
    }

    /** Cuenta cuantas raices del sujeto aparecen en un bloque del contexto. */
    private static int coincidencias(String bloque, List<String> raices) {
        String n = normalizar(bloque);
        return (int) raices.stream().filter(n::contains).count();
    }

    /** Quita la marca de plural para que "gallinas" y "gallina" se reconozcan como lo mismo. */
    private static String raiz(String palabra) {
        if (palabra.endsWith("es") && palabra.length() > 4) {
            return palabra.substring(0, palabra.length() - 2);
        }
        if (palabra.endsWith("s") && palabra.length() > 3) {
            return palabra.substring(0, palabra.length() - 1);
        }
        return palabra;
    }

    /** Detecta si el cliente pide reformular o explicar mejor la respuesta anterior. */
    private boolean esReformulacion(String textoNormalizado) {
        return MARCAS_REFORMULACION.stream().anyMatch(textoNormalizado::contains);
    }

    /** Detecta si la pregunta depende de lo dicho antes en la conversacion. */
    private boolean esSeguimiento(String textoNormalizado) {
        String t = textoNormalizado.trim();
        return t.startsWith("y ") || t.startsWith("¿y ")
                || MARCAS_SEGUIMIENTO.stream().anyMatch(t::contains);
    }

    /** Pasa a minusculas y quita tildes, para que las comparaciones no fallen por acentos. */
    private static String normalizar(String s) {
        return Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }
}
