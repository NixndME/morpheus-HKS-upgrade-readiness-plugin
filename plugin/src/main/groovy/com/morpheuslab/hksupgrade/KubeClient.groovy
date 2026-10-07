package com.morpheuslab.hksupgrade

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServerGroup
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/** Read-only Kubernetes API calls with the credentials Morpheus keeps for the HKS cluster. */
@Slf4j
class KubeClient {

    String apiUrl
    String token

    /** (method, url, body, contentType, token) -> [status, body]; swappable in tests. */
    Closure<Map> transport = KubeClient.&send

    static KubeClient of(MorpheusContext morpheus, ComputeServerGroup cluster) {
        String t = cluster.serviceToken
        if (!t && cluster.id) {
            try { t = morpheus.services.cluster.get(cluster.id)?.serviceToken } catch (Throwable ignored) { }
        }
        new KubeClient(apiUrl: cluster.serviceUrl?.replaceAll('/+$', ''), token: t)
    }

    boolean isUsable() { apiUrl && token }

    Map get(String path) { call('GET', path, null) }

    /** Raw text (pod logs). */
    String text(String path) {
        Map r = raw('GET', path, null, null)
        r.status == 200 ? r.body as String : null
    }

    /** Status and text body, also for error answers (readyz says what failed with a 500). */
    Map textAny(String path) { raw('GET', path, null, null) }

    /** When the API server's TLS certificate expires, or null if it cannot be read. */
    Date certificateExpiry() {
        if (!apiUrl?.startsWith('https')) return null
        HttpsURLConnection c = (HttpsURLConnection) new URL(apiUrl + '/version').openConnection()
        try {
            c.SSLSocketFactory = trustAll()
            c.hostnameVerifier = { String h, SSLSession s -> true }
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.connect()
            (c.serverCertificates[0] as X509Certificate).notAfter
        } catch (Throwable ignored) {
            null
        } finally {
            c.disconnect()
        }
    }

    Map call(String method, String path, Map body) {
        Map r = raw(method, path, body == null ? null : JsonOutput.toJson(body), 'application/json')
        Object data = null
        if (r.body) { try { data = new JsonSlurper().parseText(r.body as String) } catch (Exception ignored) { } }
        int status = (r.status ?: 0) as int
        [status: status, data: data, error: status >= 400 || status == 0 ? ((data instanceof Map ? data.message : null) ?: r.error ?: "HTTP ${status}") : null]
    }

    private Map raw(String method, String path, String body, String contentType) {
        if (!usable) return [status: 0, error: 'Morpheus has no API access stored for this cluster']
        try {
            transport.call(method, apiUrl + path, body, contentType, token)
        } catch (Throwable t) {
            log.debug("HKS Upgrade Readiness: ${method} ${path} failed: ${t}")
            [status: 0, error: "${t.class.simpleName}: ${t.message}"]
        }
    }

    static Map send(String method, String url, String body, String contentType, String bearer) {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection()
        try {
            if (c instanceof HttpsURLConnection) {
                // same trust Morpheus uses for this cluster's API
                ((HttpsURLConnection) c).SSLSocketFactory = trustAll()
                ((HttpsURLConnection) c).hostnameVerifier = { String h, SSLSession s -> true }
            }
            c.requestMethod = method
            c.connectTimeout = 8_000
            c.readTimeout = 120_000
            c.setRequestProperty('Accept', 'application/json, */*')
            c.setRequestProperty('Authorization', "Bearer ${bearer}")
            if (body != null) {
                c.setRequestProperty('Content-Type', contentType)
                c.doOutput = true
                c.outputStream.withStream { it.write(body.getBytes('UTF-8')) }
            }
            int status = c.responseCode
            InputStream stream = status >= 400 ? c.errorStream : c.inputStream
            [status: status, body: stream?.getText('UTF-8') ?: '']
        } finally {
            c.disconnect()
        }
    }

    static javax.net.ssl.SSLSocketFactory trustAll() {
        TrustManager[] tm = [new X509TrustManager() {
            void checkClientTrusted(X509Certificate[] chain, String authType) {}
            void checkServerTrusted(X509Certificate[] chain, String authType) {}
            X509Certificate[] getAcceptedIssuers() { new X509Certificate[0] }
        }] as TrustManager[]
        SSLContext ctx = SSLContext.getInstance('TLS')
        ctx.init(null, tm, new java.security.SecureRandom())
        ctx.socketFactory
    }
}
