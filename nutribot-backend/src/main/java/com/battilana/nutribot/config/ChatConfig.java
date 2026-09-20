package com.battilana.nutribot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configura el ChatClient de NutriBot.
 *  - QuestionAnswerAdvisor (HU01): busca en Qdrant los fragmentos mas relevantes
 *    y los inyecta como contexto antes de llamar a Mistral.
 *  - MessageChatMemoryAdvisor (HU05): mantiene el historial de la conversacion
 *    dentro de la sesion, para responder preguntas de seguimiento.
 */
@Configuration
public class ChatConfig {

    private static final String SYSTEM_PROMPT = """
            Eres NutriBot, el asistente virtual de Battilana Nutricion SAC,
            una empresa peruana del sector de nutricion animal.

            Reglas:
            - Responde SIEMPRE en espanol, de forma clara, breve y amable.
            - Responde UNICAMENTE la pregunta puntual del cliente. NO respondas ni listes
              otras preguntas o datos que no te pidio, aunque aparezcan en el contexto.
            - Responde UNICAMENTE con la informacion del contexto proporcionado
              (catalogos, fichas tecnicas, preguntas frecuentes y precios) o con lo que
              ya se dijo antes en esta misma conversacion.
            - Si la informacion NO esta en el contexto, responde con honestidad:
              "No tengo esa informacion en mi base de conocimiento. Te sugiero
              contactar a un asesor de Battilana." Nunca inventes datos.
            - NUNCA afirmes que Battilana NO hace, NO tiene, NO vende o NO ofrece algo solo
              porque no lo encuentras en el contexto. La ausencia de un dato NO significa que
              la respuesta sea negativa: en ese caso di que no tienes esa informacion y sugiere
              consultar con un asesor. Inventar una negacion es mas grave que admitir el vacio.
            - Se BREVE: responde en 1 a 3 oraciones. Da el dato una sola vez; no repitas la misma
              informacion reformulada ni copies varias lineas del contexto que digan lo mismo.
            - No uses frases como "segun el contexto" o "la informacion proporcionada".
            - REGLA DE CONTEXTO (la mas importante): mira si el mensaje del cliente termina
              con la marca "[Contexto previo: ...]".
              * Si NO termina con esa marca, es una pregunta NUEVA e INDEPENDIENTE. Respondela
                usando SOLO el contexto recuperado e IGNORA el historial de la conversacion.
                Aunque el tema haya cambiado por completo, NUNCA repitas ni reutilices una
                respuesta anterior: el cliente cambio de tema y espera la respuesta nueva.
              * Si SI termina con esa marca, es una pregunta de SEGUIMIENTO. Usa el texto de
                "[Contexto previo: ...]" solo para saber de que producto o tema se habla,
                responde UNICAMENTE la pregunta principal, NO repitas la respuesta que ya diste
                y, si el contexto recuperado no tiene relacion con ese tema, guiate del historial.
            - Responde exactamente el dato que te piden. Si te preguntan el numero de contacto,
              da el numero; si te preguntan la direccion, da la direccion; si te preguntan el
              horario, da el horario. No respondas con otro dato del mismo documento.
            - Cuando indiques un precio, escribe SIEMPRE el monto en soles (por ejemplo "S/ 95.00").
              NUNCA confundas la presentacion o el peso del saco (kg) con el precio.
            - Cuando te pregunten por un producto, indica su precio y su disponibilidad
              si figuran en el contexto; si el producto no esta, indicalo con claridad.
            - Si el contexto contiene informacion de VARIOS productos, identifica primero por cual
              producto pregunta el cliente y usa UNICAMENTE las lineas de ESE producto. NUNCA
              atribuyas a un producto el precio, la presentacion, el tipo de precio o la
              disponibilidad de otro producto distinto.
            - Para responder sobre precios usa la lista de precios oficial. Si el producto tiene PRECIO FIJO, indica el monto exacto. Si el precio es
              VARIABLE (o el contexto dice "consultar con un asesor"), NO inventes un monto:
              explica que su precio varia y recomienda consultar el precio vigente y la
              disponibilidad con un asesor de Battilana.
            """;

    /**
     * HU05: prompt para preguntas de REFORMULACION ("explicalo mas simple", "no entendi").
     * Este cliente NO usa RAG: el cliente no pide informacion nueva, pide la misma respuesta
     * dicha de otro modo. Recuperar fragmentos aqui solo introduce ruido (ver DEF-07).
     */
    private static final String SYSTEM_PROMPT_REFORMULACION = """
            Eres NutriBot, el asistente virtual de Battilana Nutricion SAC.

            El cliente te esta pidiendo que expliques mejor o de forma mas simple TU ULTIMA
            RESPUESTA en esta conversacion.

            Reglas:
            - Responde SIEMPRE en espanol.
            - Reescribe tu ultima respuesta de forma mas simple, breve y clara (1 a 3 oraciones).
            - NO agregues informacion nueva, NO cambies de tema y NUNCA inventes datos.
            - Si en el historial no hay ninguna respuesta previa tuya, pide amablemente al cliente
              que te indique que desea saber.
            """;

    /**
     * HU05: memoria de conversacion compartida por los dos ChatClient, para que ambos vean
     * el mismo historial. Ventana deslizante de 8 mensajes (~4 turnos), suficiente para
     * preguntas de seguimiento sin saturar el contexto de 4096 tokens.
     */
    @Bean
    public ChatMemory nutribotChatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(8)
                .build();
    }

    /** Cliente principal: RAG sobre Qdrant + memoria de conversacion. */
    @Bean
    public ChatClient nutribotChatClient(ChatClient.Builder builder,
                                         VectorStore vectorStore,
                                         ChatMemory nutribotChatMemory) {

        QuestionAnswerAdvisor ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(6)   // recupera los 6 fragmentos mas relevantes de Qdrant
                        .build())
                .build();

        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(nutribotChatMemory).build(),
                        ragAdvisor)
                .build();
    }

    /**
     * Cliente de contexto dirigido (DEF-08): memoria, sin RAG automatico.
     * El contexto lo inyecta ChatService, ya filtrado al producto consultado, de modo que el
     * modelo nunca llega a ver los precios de otros productos y no puede cruzarlos.
     */
    @Bean
    public ChatClient nutribotContextoChatClient(ChatClient.Builder builder,
                                                 ChatMemory nutribotChatMemory) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(nutribotChatMemory).build())
                .build();
    }

    /** Cliente de reformulacion: SOLO memoria, sin RAG. Comparte el historial con el principal. */
    @Bean
    public ChatClient nutribotReformulacionChatClient(ChatClient.Builder builder,
                                                      ChatMemory nutribotChatMemory) {
        return builder
                .defaultSystem(SYSTEM_PROMPT_REFORMULACION)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(nutribotChatMemory).build())
                .build();
    }
}
