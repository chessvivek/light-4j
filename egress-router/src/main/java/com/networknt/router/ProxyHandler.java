/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.networknt.router;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.Channel;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.security.cert.CertificateEncodingException;

import io.undertow.UndertowLogger;
import io.undertow.UndertowMessages;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.ProxyCallback;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.ChannelExceptionHandler;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.IoUtils;
import org.xnio.StreamConnection;
import org.xnio.XnioExecutor;
import org.xnio.channels.StreamSinkChannel;
import io.undertow.attribute.ExchangeAttribute;
import io.undertow.attribute.ExchangeAttributes;
import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.client.ContinueNotification;
import io.undertow.client.ProxiedRequestAttachments;
import io.undertow.client.PushCallback;
import io.undertow.io.IoCallback;
import io.undertow.io.Sender;
import io.undertow.predicate.IdempotentPredicate;
import io.undertow.predicate.Predicate;
import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.HttpUpgradeListener;
import io.undertow.server.RenegotiationRequiredException;
import io.undertow.server.SSLSessionInfo;
import io.undertow.server.protocol.http.HttpAttachments;
import io.undertow.server.protocol.http.HttpContinue;
import io.undertow.util.Attachable;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Certificates;
import io.undertow.util.CopyOnWriteMap;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.NetworkUtils;
import io.undertow.util.SameThreadExecutor;
import io.undertow.util.StatusCodes;
import io.undertow.util.Transfer;
import io.undertow.util.WorkerUtils;
import java.util.List;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * An HTTP handler which proxies content to a remote server.
 * <p>
 * This handler acts like a filter. The {@link ProxyClient} has a chance to decide if it
 * knows how to proxy the request. If it does then it will provide a connection that can
 * used to connect to the remote server, otherwise the next handler will be invoked and the
 * request will proceed as normal.
 *
 * This handler uses non blocking IO
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public class ProxyHandler implements HttpHandler {
    private static final Logger logger = LoggerFactory.getLogger(ProxyHandler.class);

    public static final String UTF_8 = StandardCharsets.UTF_8.name();

    private static final AttachmentKey<ProxyConnection> CONNECTION = AttachmentKey.create(ProxyConnection.class);
    private static final AttachmentKey<HttpServerExchange> EXCHANGE = AttachmentKey.create(HttpServerExchange.class);
    private static final AttachmentKey<XnioExecutor.Key> TIMEOUT_KEY = AttachmentKey.create(XnioExecutor.Key.class);

    private final ProxyClient proxyClient;
    private final int maxRequestTime;
    /**
     * Map of additional headers to add to the request.
     */
    private final Map<HttpString, ExchangeAttribute> requestHeaders = new CopyOnWriteMap<>();

    private final HttpHandler next;

    private volatile boolean rewriteHostHeader;
    private volatile boolean reuseXForwarded;
    private volatile int maxConnectionRetries;

    private final Predicate idempotentRequestPredicate;

    ProxyHandler(ProxyHandler.Builder builder) {
        this.proxyClient = builder.proxyClient;
        this.maxRequestTime = builder.maxRequestTime;
        this.next = builder.next;
        this.rewriteHostHeader = builder.rewriteHostHeader;
        this.reuseXForwarded = builder.reuseXForwarded;
        this.maxConnectionRetries = builder.maxConnectionRetries;
        this.idempotentRequestPredicate = builder.idempotentRequestPredicate;
        for(Map.Entry<HttpString, ExchangeAttribute> e : builder.requestHeaders.entrySet()) {
            requestHeaders.put(e.getKey(), e.getValue());
        }
    }

    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        final ProxyClient.ProxyTarget target = proxyClient.findTarget(exchange);
        if (target == null) {
            logger.debug("No proxy target for request to {}", exchange.getRequestURL());
            next.handleRequest(exchange);
            return;
        }
        if(exchange.isResponseStarted()) {
            //we can't proxy a request that has already started, this is basically a server configuration error
            UndertowLogger.REQUEST_LOGGER.cannotProxyStartedRequest(exchange);
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
            exchange.endExchange();
            return;
        }
        final long timeout = maxRequestTime > 0 ? System.currentTimeMillis() + maxRequestTime : 0;
        int maxRetries = maxConnectionRetries;
        if(target instanceof ProxyClient.MaxRetriesProxyTarget) {
            maxRetries = Math.max(maxRetries, ((ProxyClient.MaxRetriesProxyTarget) target).getMaxRetries());
        }
        final ProxyHandler.ProxyClientHandler clientHandler = new ProxyHandler.ProxyClientHandler(exchange, target, timeout, maxRetries, idempotentRequestPredicate);
        if (timeout > 0) {
            final XnioExecutor.Key key = WorkerUtils.executeAfter(exchange.getIoThread(), new Runnable() {
                @Override
                public void run() {
                    clientHandler.cancel(exchange);
                }
            }, maxRequestTime, TimeUnit.MILLISECONDS);
            exchange.putAttachment(TIMEOUT_KEY, key);
            exchange.addExchangeCompleteListener(new ExchangeCompletionListener() {
                @Override
                public void exchangeEvent(HttpServerExchange exchange, NextListener nextListener) {
                    key.remove();
                    nextListener.proceed();
                }
            });
        }
        exchange.dispatch(exchange.isInIoThread() ? SameThreadExecutor.INSTANCE : exchange.getIoThread(), clientHandler);
    }

    static void copyHeaders(final HeaderMap to, final HeaderMap from) {
        long f = from.fastIterateNonEmpty();
        HeaderValues values;
        while (f != -1L) {
            values = from.fiCurrent(f);
            if(!to.contains(values.getHeaderName())) {
                //don't over write existing headers, normally the map will be empty, if it is not we assume it is not for a reason
                to.putAll(values.getHeaderName(), values);
            }
            f = from.fiNextNonEmpty(f);
        }
    }

    public ProxyClient getProxyClient() {
        return proxyClient;
    }

    @Override
    public String toString() {
        List<ProxyClient.ProxyTarget> proxyTargets = proxyClient.getAllTargets();
        if (proxyTargets.isEmpty()){
            return "ProxyHandler - "+proxyClient.getClass().getSimpleName();
        }
        if(proxyTargets.size()==1 && !rewriteHostHeader){
            return "reverse-proxy( '" + proxyTargets.get(0).toString() + "' )";
        } else {
            String outputResult = "reverse-proxy( { '" + proxyTargets.stream().map(s -> s.toString()).collect(Collectors.joining("', '")) + "' }";
            if(rewriteHostHeader){
                outputResult += ", rewrite-host-header=true";
            }
            return outputResult+" )";
        }
    }

    private final class ProxyClientHandler implements ProxyCallback<ProxyConnection>, Runnable {

        private int tries;

        private final long timeout;
        private final int maxRetryAttempts;
        private final HttpServerExchange exchange;
        private final Predicate idempotentPredicate;
        private ProxyClient.ProxyTarget target;

        ProxyClientHandler(HttpServerExchange exchange, ProxyClient.ProxyTarget target, long timeout, int maxRetryAttempts, Predicate idempotentPredicate) {
            this.exchange = exchange;
            this.timeout = timeout;
            this.maxRetryAttempts = maxRetryAttempts;
            this.target = target;
            this.idempotentPredicate = idempotentPredicate;
        }

        @Override
        public void run() {
            proxyClient.getConnection(target, exchange, this, -1, TimeUnit.MILLISECONDS);
        }

        @Override
        public void completed(final HttpServerExchange exchange, final ProxyConnection connection) {
            exchange.putAttachment(CONNECTION, connection);
            exchange.dispatch(SameThreadExecutor.INSTANCE, new ProxyHandler.ProxyAction(connection, exchange, requestHeaders, rewriteHostHeader, reuseXForwarded, exchange.isRequestComplete() ? this : null, idempotentPredicate));
        }

        @Override
        public void failed(final HttpServerExchange exchange) {
            final long time = System.currentTimeMillis();
            if (tries++ < maxRetryAttempts) {
                if (timeout > 0 && time > timeout) {
                    cancel(exchange);
                } else {
                    target = proxyClient.findTarget(exchange);
                    if (target != null) {
                        final long remaining = timeout > 0 ? timeout - time : -1;
                        proxyClient.getConnection(target, exchange, this, remaining, TimeUnit.MILLISECONDS);
                    } else {
                        couldNotResolveBackend(exchange); // The context was registered when we started, so return 503
                    }
                }
            } else {
                couldNotResolveBackend(exchange);
            }
        }

        @Override
        public void queuedRequestFailed(HttpServerExchange exchange) {
            failed(exchange);
        }

        @Override
        public void couldNotResolveBackend(HttpServerExchange exchange) {
            if (exchange.isResponseStarted()) {
                IoUtils.safeClose(exchange.getConnection());
            } else {
                exchange.setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
                exchange.endExchange();
            }
        }

        void cancel(final HttpServerExchange exchange) {
            //NOTE: this method is called only in context of timeouts.
            final ProxyConnection connectionAttachment = exchange.getAttachment(CONNECTION);
            if (connectionAttachment != null) {
                ClientConnection clientConnection = connectionAttachment.getConnection();
                UndertowLogger.PROXY_REQUEST_LOGGER.timingOutRequest(clientConnection.getPeerAddress() + "" + exchange.getRequestURI());
                IoUtils.safeClose(clientConnection);
            } else {
                UndertowLogger.PROXY_REQUEST_LOGGER.timingOutRequest(exchange.getRequestURI());
            }
            if (exchange.isResponseStarted()) {
                IoUtils.safeClose(exchange.getConnection());
            } else {
                exchange.setStatusCode(StatusCodes.GATEWAY_TIME_OUT);
                exchange.endExchange();
            }
        }

    }

    private static class ProxyAction implements Runnable {
        private final ProxyConnection clientConnection;
        private final HttpServerExchange exchange;
        private final Map<HttpString, ExchangeAttribute> requestHeaders;
        private final boolean rewriteHostHeader;
        private final boolean reuseXForwarded;
        private final ProxyHandler.ProxyClientHandler proxyClientHandler;
        private final Predicate idempotentPredicate;
        private final RouterConfig config = RouterConfig.load();

        ProxyAction(final ProxyConnection clientConnection, final HttpServerExchange exchange, Map<HttpString, ExchangeAttribute> requestHeaders,
                    boolean rewriteHostHeader, boolean reuseXForwarded, ProxyHandler.ProxyClientHandler proxyClientHandler, Predicate idempotentPredicate) {
            this.clientConnection = clientConnection;
            this.exchange = exchange;
            this.requestHeaders = requestHeaders;
            this.rewriteHostHeader = rewriteHostHeader;
            this.reuseXForwarded = reuseXForwarded;
            this.proxyClientHandler = proxyClientHandler;
            this.idempotentPredicate = idempotentPredicate;
        }

        @Override
        public void run() {
            final ClientRequest request = new ClientRequest();

            String targetURI = exchange.getRequestURI();
            if(exchange.isHostIncludedInRequestURI()) {
                // this part of the code will never reach in the light-router
                int uriPart = targetURI.indexOf("//");
                if(uriPart != -1) {
                    uriPart = targetURI.indexOf("/", uriPart + 2);
                    if(uriPart != -1) {
                        targetURI = targetURI.substring(uriPart);
                    }
                }
            }

            if(!exchange.getResolvedPath().isEmpty() && targetURI.startsWith(exchange.getResolvedPath())) {
                targetURI = targetURI.substring(exchange.getResolvedPath().length());
            }

            StringBuilder requestURI = new StringBuilder();
            if(!clientConnection.getTargetPath().isEmpty()
                    && (!clientConnection.getTargetPath().equals("/") || targetURI.isEmpty())) {
                requestURI.append(clientConnection.getTargetPath());
            }
            // handle the url rewrite here.
            if(config.getUrlRewriteRules().size() > 0) {
                boolean matched = false;
                for(UrlRewriteRule rule : config.urlRewriteRules) {
                    Matcher matcher = rule.getPattern().matcher(targetURI);
                    if(matcher.matches()) {
                        matched = true;
                        requestURI.append(matcher.replaceAll(rule.getReplace()));
                        break;
                    }
                }
                // if no matched rule in the list, use the original targetURI.
                if(!matched) requestURI.append(targetURI);
            } else {
                // there is no url rewrite rules, so use the original targetURI
                requestURI.append(targetURI);
            }

            String qs = exchange.getQueryString();
            if (qs != null && !qs.isEmpty()) {
                requestURI.append('?');
                requestURI.append(qs);
            }
            // handler the method rewrite here.
            HttpString method = exchange.getRequestMethod();
            if(config.getMethodRewriteRules().size() > 0) {
                for(MethodRewriteRule rule: config.getMethodRewriteRules()) {
                    if(targetURI.equals(rule.requestPath) && method.toString().equals(rule.getSourceMethod())) {
                        if(logger.isDebugEnabled()) logger.debug("Rewrite HTTP method from " + rule.getSourceMethod() + " to " + rule.getTargetMethod());
                        method = new HttpString(rule.targetMethod);
                    }
                }
            }
            request.setPath(requestURI.toString())
                    .setMethod(method);
            final HeaderMap inboundRequestHeaders = exchange.getRequestHeaders();
            final HeaderMap outboundRequestHeaders = request.getRequestHeaders();
            copyHeaders(outboundRequestHeaders, inboundRequestHeaders);

            if (!exchange.isPersistent()) {
                //just because the client side is non-persistent
                //we don't want to close the connection to the backend
                outboundRequestHeaders.put(Headers.CONNECTION, "keep-alive");
            }
            if("h2c".equals(exchange.getRequestHeaders().getFirst(Headers.UPGRADE))) {
                //we don't allow h2c upgrade requests to be passed through to the backend
                exchange.getRequestHeaders().remove(Headers.UPGRADE);
                outboundRequestHeaders.put(Headers.CONNECTION, "keep-alive");
            }

            for (Map.Entry<HttpString, ExchangeAttribute> entry : requestHeaders.entrySet()) {
                String headerValue = entry.getValue().readAttribute(exchange);
                if (headerValue == null || headerValue.isEmpty()) {
                    outboundRequestHeaders.remove(entry.getKey());
                } else {
                    outboundRequestHeaders.put(entry.getKey(), headerValue.replace('\n', ' '));
                }
            }
            final String remoteHost;
            final SocketAddress address = exchange.getSourceAddress();
            if (address != null) {
                remoteHost = ((InetSocketAddress) address).getHostString();
                if(!((InetSocketAddress) address).isUnresolved()) {
                    request.putAttachment(ProxiedRequestAttachments.REMOTE_ADDRESS, ((InetSocketAddress) address).getAddress().getHostAddress());
                }
            } else {
                //should never happen, unless this is some form of mock request
                remoteHost = "localhost";
            }

            request.putAttachment(ProxiedRequestAttachments.REMOTE_HOST, remoteHost);

            if (reuseXForwarded && request.getRequestHeaders().contains(Headers.X_FORWARDED_FOR)) {
                // We have an existing header so we shall simply append the host to the existing list
                final String current = request.getRequestHeaders().getFirst(Headers.X_FORWARDED_FOR);
                if (current == null || current.isEmpty()) {
                    // It was empty so just add it
                    request.getRequestHeaders().put(Headers.X_FORWARDED_FOR, remoteHost);
                }
                else {
                    // Add the new entry and reset the existing header
                    request.getRequestHeaders().put(Headers.X_FORWARDED_FOR, current + "," + remoteHost);
                }
            }
            else {
                // No existing header or not allowed to reuse the header so set it here
                request.getRequestHeaders().put(Headers.X_FORWARDED_FOR, remoteHost);
            }

            //if we don't support push set a header saying so
            //this is non standard, and a problem with the HTTP2 spec, but they did not want to listen
            if(!exchange.getConnection().isPushSupported() && clientConnection.getConnection().isPushSupported()) {
                request.getRequestHeaders().put(Headers.X_DISABLE_PUSH, "true");
            }

            // Set the protocol header and attachment
            if(reuseXForwarded && exchange.getRequestHeaders().contains(Headers.X_FORWARDED_PROTO)) {
                final String proto = exchange.getRequestHeaders().getFirst(Headers.X_FORWARDED_PROTO);
                request.putAttachment(ProxiedRequestAttachments.IS_SSL, proto.equals("https"));
            } else {
                final String proto = exchange.getRequestScheme().equals("https") ? "https" : "http";
                request.getRequestHeaders().put(Headers.X_FORWARDED_PROTO, proto);
                request.putAttachment(ProxiedRequestAttachments.IS_SSL, proto.equals("https"));
            }

            // Set the server name
            if(reuseXForwarded && exchange.getRequestHeaders().contains(Headers.X_FORWARDED_SERVER)) {
                final String hostName = exchange.getRequestHeaders().getFirst(Headers.X_FORWARDED_SERVER);
                request.putAttachment(ProxiedRequestAttachments.SERVER_NAME, hostName);
            } else {
                final String hostName = exchange.getHostName();
                request.getRequestHeaders().put(Headers.X_FORWARDED_SERVER, hostName);
                request.putAttachment(ProxiedRequestAttachments.SERVER_NAME, hostName);
            }
            if(!exchange.getRequestHeaders().contains(Headers.X_FORWARDED_HOST)) {
                final String hostName = exchange.getHostName();
                if(hostName != null) {
                    request.getRequestHeaders().put(Headers.X_FORWARDED_HOST, NetworkUtils.formatPossibleIpv6Address(hostName));
                }
            }

            // Set the port
            if(reuseXForwarded && exchange.getRequestHeaders().contains(Headers.X_FORWARDED_PORT)) {
                try {
                    int port = Integer.parseInt(exchange.getRequestHeaders().getFirst(Headers.X_FORWARDED_PORT));
                    request.putAttachment(ProxiedRequestAttachments.SERVER_PORT, port);
                } catch (NumberFormatException e) {
                    int port = exchange.getConnection().getLocalAddress(InetSocketAddress.class).getPort();
                    request.getRequestHeaders().put(Headers.X_FORWARDED_PORT, port);
                    request.putAttachment(ProxiedRequestAttachments.SERVER_PORT, port);
                }
            } else {
                int port = exchange.getHostPort();
                request.getRequestHeaders().put(Headers.X_FORWARDED_PORT, port);
                request.putAttachment(ProxiedRequestAttachments.SERVER_PORT, port);
            }

            SSLSessionInfo sslSessionInfo = exchange.getConnection().getSslSessionInfo();
            if (sslSessionInfo != null) {
                Certificate[] peerCertificates;
                try {
                    peerCertificates = sslSessionInfo.getPeerCertificates();
                    if (peerCertificates.length > 0) {
                        request.putAttachment(ProxiedRequestAttachments.SSL_CERT, Certificates.toPem(peerCertificates[0]));
                    }
                } catch (SSLPeerUnverifiedException | CertificateEncodingException | RenegotiationRequiredException e) {
                    //ignore
                }
                request.putAttachment(ProxiedRequestAttachments.SSL_CYPHER, sslSessionInfo.getCipherSuite());
                request.putAttachment(ProxiedRequestAttachments.SSL_SESSION_ID, sslSessionInfo.getSessionId());
                request.putAttachment(ProxiedRequestAttachments.SSL_KEY_SIZE, sslSessionInfo.getKeySize());
            }

            if(rewriteHostHeader) {
                InetSocketAddress targetAddress = clientConnection.getConnection().getPeerAddress(InetSocketAddress.class);
                request.getRequestHeaders().put(Headers.HOST, targetAddress.getHostString() + ":" + targetAddress.getPort());
                request.getRequestHeaders().put(Headers.X_FORWARDED_HOST, exchange.getRequestHeaders().getFirst(Headers.HOST));
            }
            if(logger.isDebugEnabled()) {
                logger.debug("Sending request {} to target {} for exchange {}", request, clientConnection.getConnection().getPeerAddress(), exchange);
            }
            //handle content
            //if the frontend is HTTP/2 then we may need to add a Transfer-Encoding header, to indicate to the backend
            //that there is content
            if(!request.getRequestHeaders().contains(Headers.TRANSFER_ENCODING) && !request.getRequestHeaders().contains(Headers.CONTENT_LENGTH)) {
                if(!exchange.isRequestComplete()) {
                    request.getRequestHeaders().put(Headers.TRANSFER_ENCODING, Headers.CHUNKED.toString());
                }
            }


            clientConnection.getConnection().sendRequest(request, new ClientCallback<ClientExchange>() {
                @Override
                public void completed(final ClientExchange result) {

                    if(logger.isDebugEnabled()) {
                        logger.debug("Sent request {} to target {} for exchange {}", request, remoteHost, exchange);
                    }
                    result.putAttachment(EXCHANGE, exchange);

                    boolean requiresContinueResponse = HttpContinue.requiresContinueResponse(exchange);
                    if (requiresContinueResponse) {
                        result.setContinueHandler(new ContinueNotification() {
                            @Override
                            public void handleContinue(final ClientExchange clientExchange) {
                                if(logger.isDebugEnabled()) {
                                    logger.debug("Received continue response to request {} to target {} for exchange {}", request, clientConnection.getConnection().getPeerAddress(), exchange);
                                }
                                HttpContinue.sendContinueResponse(exchange, new IoCallback() {
                                    @Override
                                    public void onComplete(final HttpServerExchange exchange, final Sender sender) {
                                        //don't care
                                    }

                                    @Override
                                    public void onException(final HttpServerExchange exchange, final Sender sender, final IOException exception) {
                                        IoUtils.safeClose(clientConnection.getConnection());
                                        exchange.endExchange();
                                        UndertowLogger.REQUEST_IO_LOGGER.ioException(exception);
                                    }
                                });
                            }
                        });
                    }

                    //handle server push
                    if(exchange.getConnection().isPushSupported() && result.getConnection().isPushSupported()) {
                        result.setPushHandler(new PushCallback() {
                            @Override
                            public boolean handlePush(ClientExchange originalRequest, final ClientExchange pushedRequest) {

                                if(logger.isDebugEnabled()) {
                                    logger.debug("Sending push request {} received from {} to target {} for exchange {}", pushedRequest.getRequest(), request, remoteHost, exchange);
                                }
                                final ClientRequest request = pushedRequest.getRequest();
                                exchange.getConnection().pushResource(request.getPath(), request.getMethod(), request.getRequestHeaders(), new HttpHandler() {
                                    @Override
                                    public void handleRequest(final HttpServerExchange exchange) throws Exception {
                                        String path = request.getPath();
                                        int i = path.indexOf("?");
                                        if(i > 0) {
                                            path = path.substring(0, i);
                                        }

                                        exchange.dispatch(SameThreadExecutor.INSTANCE, new ProxyHandler.ProxyAction(new ProxyConnection(pushedRequest.getConnection(), path), exchange, requestHeaders, rewriteHostHeader, reuseXForwarded, null, idempotentPredicate));
                                    }
                                });
                                return true;
                            }
                        });
                    }


                    result.setResponseListener(new ProxyHandler.ResponseCallback(exchange, proxyClientHandler, idempotentPredicate));
                    final ProxyHandler.IoExceptionHandler handler = new ProxyHandler.IoExceptionHandler(exchange, clientConnection.getConnection());
                    if(requiresContinueResponse) {
                        try {
                            if(!result.getRequestChannel().flush()) {
                                result.getRequestChannel().getWriteSetter().set(ChannelListeners.flushingChannelListener(new ChannelListener<StreamSinkChannel>() {
                                    @Override
                                    public void handleEvent(StreamSinkChannel channel) {
                                        Transfer.initiateTransfer(exchange.getRequestChannel(), result.getRequestChannel(), ChannelListeners.closingChannelListener(), new ProxyHandler.HTTPTrailerChannelListener(exchange, result, exchange, proxyClientHandler, idempotentPredicate), handler, handler, exchange.getConnection().getByteBufferPool());

                                    }
                                }, handler));
                                result.getRequestChannel().resumeWrites();
                                return;
                            }
                        } catch (IOException e) {
                            handler.handleException(result.getRequestChannel(), e);
                        }
                    }
                    ProxyHandler.HTTPTrailerChannelListener trailerListener = new ProxyHandler.HTTPTrailerChannelListener(exchange, result, exchange, proxyClientHandler, idempotentPredicate);
                    if(!exchange.isRequestComplete()) {
                        Transfer.initiateTransfer(exchange.getRequestChannel(), result.getRequestChannel(), ChannelListeners.closingChannelListener(), trailerListener, handler, handler, exchange.getConnection().getByteBufferPool());
                    } else {
                        trailerListener.handleEvent(result.getRequestChannel());
                    }

                }

                @Override
                public void failed(IOException e) {
                    handleFailure(exchange, proxyClientHandler, idempotentPredicate, e);
                }
            });


        }
    }

    static void handleFailure(HttpServerExchange exchange, ProxyHandler.ProxyClientHandler proxyClientHandler, Predicate idempotentRequestPredicate, IOException e) {
        UndertowLogger.PROXY_REQUEST_LOGGER.proxyRequestFailed(exchange.getRequestURI(), e);
        if(exchange.isResponseStarted()) {
            IoUtils.safeClose(exchange.getConnection());
        } else if(idempotentRequestPredicate.resolve(exchange) && proxyClientHandler != null) {
            proxyClientHandler.failed(exchange); //this will attempt a retry if configured to do so
        } else {
            exchange.setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
            exchange.endExchange();
        }
    }

    private static final class ResponseCallback implements ClientCallback<ClientExchange> {

        private final HttpServerExchange exchange;
        private final ProxyHandler.ProxyClientHandler proxyClientHandler;
        private final Predicate idempotentPredicate;

        private ResponseCallback(HttpServerExchange exchange, ProxyHandler.ProxyClientHandler proxyClientHandler, Predicate idempotentPredicate) {
            this.exchange = exchange;
            this.proxyClientHandler = proxyClientHandler;
            this.idempotentPredicate = idempotentPredicate;
        }

        @Override
        public void completed(final ClientExchange result) {

            final ClientResponse response = result.getResponse();

            if(logger.isDebugEnabled()) {
                logger.debug("Received response {} for request {} for exchange {}", response, result.getRequest(), exchange);
            }
            final HeaderMap inboundResponseHeaders = response.getResponseHeaders();
            final HeaderMap outboundResponseHeaders = exchange.getResponseHeaders();
            exchange.setStatusCode(response.getResponseCode());
            copyHeaders(outboundResponseHeaders, inboundResponseHeaders);

            if (exchange.isUpgrade()) {

                exchange.upgradeChannel(new HttpUpgradeListener() {
                    @Override
                    public void handleUpgrade(StreamConnection streamConnection, HttpServerExchange exchange) {

                        if(logger.isDebugEnabled()) {
                            logger.debug("Upgraded request {} to for exchange {}", result.getRequest(), exchange);
                        }
                        StreamConnection clientChannel = null;
                        try {
                            clientChannel = result.getConnection().performUpgrade();

                            final ProxyHandler.ClosingExceptionHandler handler = new ProxyHandler.ClosingExceptionHandler(streamConnection, clientChannel);
                            Transfer.initiateTransfer(clientChannel.getSourceChannel(), streamConnection.getSinkChannel(), ChannelListeners.closingChannelListener(), ChannelListeners.writeShutdownChannelListener(ChannelListeners.<StreamSinkChannel>flushingChannelListener(ChannelListeners.closingChannelListener(), ChannelListeners.closingChannelExceptionHandler()), ChannelListeners.closingChannelExceptionHandler()), handler, handler, result.getConnection().getBufferPool());
                            Transfer.initiateTransfer(streamConnection.getSourceChannel(), clientChannel.getSinkChannel(), ChannelListeners.closingChannelListener(), ChannelListeners.writeShutdownChannelListener(ChannelListeners.<StreamSinkChannel>flushingChannelListener(ChannelListeners.closingChannelListener(), ChannelListeners.closingChannelExceptionHandler()), ChannelListeners.closingChannelExceptionHandler()), handler, handler, result.getConnection().getBufferPool());

                        } catch (IOException e) {
                            IoUtils.safeClose(streamConnection, clientChannel);
                        }
                    }
                });
            }
            final ProxyHandler.IoExceptionHandler handler = new ProxyHandler.IoExceptionHandler(exchange, result.getConnection());
            Transfer.initiateTransfer(result.getResponseChannel(), exchange.getResponseChannel(), ChannelListeners.closingChannelListener(), new ProxyHandler.HTTPTrailerChannelListener(result, exchange, exchange, proxyClientHandler, idempotentPredicate), handler, handler, exchange.getConnection().getByteBufferPool());
        }

        @Override
        public void failed(IOException e) {
            handleFailure(exchange, proxyClientHandler, idempotentPredicate, e);
        }
    }

    private static final class HTTPTrailerChannelListener implements ChannelListener<StreamSinkChannel> {

        private final Attachable source;
        private final Attachable target;
        private final HttpServerExchange exchange;
        private final ProxyHandler.ProxyClientHandler proxyClientHandler;
        private final Predicate idempotentPredicate;

        private HTTPTrailerChannelListener(final Attachable source, final Attachable target, HttpServerExchange exchange, ProxyHandler.ProxyClientHandler proxyClientHandler, Predicate idempotentPredicate) {
            this.source = source;
            this.target = target;
            this.exchange = exchange;
            this.proxyClientHandler = proxyClientHandler;
            this.idempotentPredicate = idempotentPredicate;
        }

        @Override
        public void handleEvent(final StreamSinkChannel channel) {
            HeaderMap trailers = source.getAttachment(HttpAttachments.REQUEST_TRAILERS);
            if (trailers != null) {
                target.putAttachment(HttpAttachments.RESPONSE_TRAILERS, trailers);
            }
            try {
                channel.shutdownWrites();
                if (!channel.flush()) {
                    channel.getWriteSetter().set(ChannelListeners.flushingChannelListener(new ChannelListener<StreamSinkChannel>() {
                        @Override
                        public void handleEvent(StreamSinkChannel channel) {
                            channel.suspendWrites();
                            channel.getWriteSetter().set(null);
                        }
                    }, ChannelListeners.closingChannelExceptionHandler()));
                    channel.resumeWrites();
                } else {
                    channel.getWriteSetter().set(null);
                    channel.shutdownWrites();
                }
            } catch (IOException e) {
                handleFailure(exchange, proxyClientHandler, idempotentPredicate, e);
            } catch (Exception e) {
                handleFailure(exchange, proxyClientHandler, idempotentPredicate, new IOException(e));
            }

        }
    }

    private static final class IoExceptionHandler implements ChannelExceptionHandler<Channel> {

        private final HttpServerExchange exchange;
        private final ClientConnection clientConnection;

        private IoExceptionHandler(HttpServerExchange exchange, ClientConnection clientConnection) {
            this.exchange = exchange;
            this.clientConnection = clientConnection;
        }

        @Override
        public void handleException(Channel channel, IOException exception) {
            IoUtils.safeClose(channel);
            IoUtils.safeClose(clientConnection);
            if (exchange.isResponseStarted()) {
                UndertowLogger.REQUEST_IO_LOGGER.debug("Exception reading from target server", exception);
                if (!exchange.isResponseStarted()) {
                    exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                    exchange.endExchange();
                } else {
                    IoUtils.safeClose(exchange.getConnection());
                }
            } else {
                UndertowLogger.REQUEST_IO_LOGGER.ioException(exception);
                exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                exchange.endExchange();
            }
        }
    }

    @Deprecated
    public ProxyHandler setMaxConnectionRetries(int maxConnectionRetries) {
        this.maxConnectionRetries = maxConnectionRetries;
        return this;
    }

    public boolean isRewriteHostHeader() {
        return rewriteHostHeader;
    }

    @Deprecated
    public ProxyHandler setRewriteHostHeader(boolean rewriteHostHeader) {
        this.rewriteHostHeader = rewriteHostHeader;
        return this;
    }

    public boolean isReuseXForwarded() {
        return reuseXForwarded;
    }

    @Deprecated
    public ProxyHandler setReuseXForwarded(boolean reuseXForwarded) {
        this.reuseXForwarded = reuseXForwarded;
        return this;
    }

    public int getMaxConnectionRetries() {
        return maxConnectionRetries;
    }

    public Predicate getIdempotentRequestPredicate() {
        return idempotentRequestPredicate;
    }

    private static final class ClosingExceptionHandler implements ChannelExceptionHandler<Channel> {

        private final Closeable[] toClose;

        private ClosingExceptionHandler(Closeable... toClose) {
            this.toClose = toClose;
        }


        @Override
        public void handleException(Channel channel, IOException exception) {
            IoUtils.safeClose(channel);
            IoUtils.safeClose(toClose);
        }
    }

    public static ProxyHandler.Builder builder() {
        return new ProxyHandler.Builder();
    }

    public static class Builder {

        private ProxyClient proxyClient;
        private int maxRequestTime = -1;
        private final Map<HttpString, ExchangeAttribute> requestHeaders = new CopyOnWriteMap<>();
        private HttpHandler next = ResponseCodeHandler.HANDLE_404;
        private boolean rewriteHostHeader;
        private boolean reuseXForwarded;
        private int maxConnectionRetries = 1;
        private Predicate idempotentRequestPredicate = IdempotentPredicate.INSTANCE;

        Builder() {}


        public ProxyClient getProxyClient() {
            return proxyClient;
        }

        public ProxyHandler.Builder setProxyClient(ProxyClient proxyClient) {
            if(proxyClient == null) {
                throw UndertowMessages.MESSAGES.argumentCannotBeNull("proxyClient");
            }
            this.proxyClient = proxyClient;
            return this;
        }

        public int getMaxRequestTime() {
            return maxRequestTime;
        }

        public ProxyHandler.Builder setMaxRequestTime(int maxRequestTime) {
            this.maxRequestTime = maxRequestTime;
            return this;
        }

        public Map<HttpString, ExchangeAttribute> getRequestHeaders() {
            return Collections.unmodifiableMap(requestHeaders);
        }

        public ProxyHandler.Builder addRequestHeader(HttpString header, ExchangeAttribute value) {
            this.requestHeaders.put(header, value);
            return this;
        }

        public HttpHandler getNext() {
            return next;
        }

        public ProxyHandler.Builder setNext(HttpHandler next) {
            this.next = next;
            return this;
        }

        public boolean isRewriteHostHeader() {
            return rewriteHostHeader;
        }

        public ProxyHandler.Builder setRewriteHostHeader(boolean rewriteHostHeader) {
            this.rewriteHostHeader = rewriteHostHeader;
            return this;
        }

        public boolean isReuseXForwarded() {
            return reuseXForwarded;
        }

        public ProxyHandler.Builder setReuseXForwarded(boolean reuseXForwarded) {
            this.reuseXForwarded = reuseXForwarded;
            return this;
        }

        public int getMaxConnectionRetries() {
            return maxConnectionRetries;
        }

        public ProxyHandler.Builder setMaxConnectionRetries(int maxConnectionRetries) {
            this.maxConnectionRetries = maxConnectionRetries;
            return this;
        }

        public Predicate getIdempotentRequestPredicate() {
            return idempotentRequestPredicate;
        }

        public ProxyHandler.Builder setIdempotentRequestPredicate(Predicate idempotentRequestPredicate) {
            if(idempotentRequestPredicate == null) {
                throw UndertowMessages.MESSAGES.argumentCannotBeNull("idempotentRequestPredicate");
            }
            this.idempotentRequestPredicate = idempotentRequestPredicate;
            return this;
        }

        public ProxyHandler build() {
            return new ProxyHandler(this);
        }
    }

}
