package dev.pepsi.questrank.client.net;

/**
 * Salida C2S del cliente (puerto inyectable para pruebas deterministas): solicitudes y
 * suscripciones del protocolo §9.1.
 */
public interface ClientTransport {

    void sendRequest(long requestId, Long knownEpoch, long knownRev);

    void sendSubscribe(boolean subscribed);
}
