package com.lagradost.nicehttp

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/**
 * (v0.2.2) DNS that tries a DNS-over-HTTPS resolver first and falls
 * back to the system resolver when DoH fails. Without this, a DoH
 * endpoint that is unreachable on the phone's network (or slow) makes
 * EVERY request fail — including the .cs3 downloads — while the
 * manifest fetches still look fine because they are served from the
 * OkHttp cache. Upstream CloudStream defaults to system DNS; our
 * default was Cloudflare DoH with no fallback at all.
 */
class FallbackDns(
    private val primary: Dns,
    private val fallback: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            primary.lookup(hostname)
        } catch (e: Exception) {
            fallback.lookup(hostname)
        }
    }
}

fun OkHttpClient.Builder.addGenericDns(url: String, ips: List<String>) = dns(
    FallbackDns(
        DnsOverHttps
            .Builder()
            .client(build())
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(
                ips.map { InetAddress.getByName(it) }
            )
            .build()
    )
)