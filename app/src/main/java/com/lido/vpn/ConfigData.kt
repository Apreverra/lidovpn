package com.lido.vpn

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object NetworkClient {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
}

object ConfigData {
    val providers = listOf(
        ConfigProvider(
            id = "whoahaow",
            name = "RJSXRD (whoahaow)",
            owner = "whoahaow",
            repo = "rjsxrd",
            categories = listOf(
                ConfigCategory("Обычные конфиги (default/)", listOf(
                    ConfigSource("1", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/default/1.txt"),
                    ConfigSource("6", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/default/6.txt"),
                    ConfigSource("22", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/default/22.txt"),
                    ConfigSource("23", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/default/23.txt"),
                    ConfigSource("24", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/default/24.txt"),
                    ConfigSource("25", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/default/25.txt"),
                    ConfigSource("all.txt", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/default/all.txt"),
                    ConfigSource("all-secure.txt", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/default/all-secure.txt")
                )),
                ConfigCategory("Конфиги для обхода SNI/CIDR (bypass/)", mutableListOf(
                    ConfigSource("bypass-all", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/bypass/bypass-all.txt")
                ).apply {
                    addAll((1..15).map { ConfigSource("bypass-$it", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/bypass/bypass-$it.txt") })
                }),
                ConfigCategory("Небезопасные конфиги (bypass-unsecure/)", mutableListOf(
                    ConfigSource("bypass-unsecure-all", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/bypass-unsecure/bypass-unsecure-all.txt")
                ).apply {
                    addAll((1..16).map { ConfigSource("bypass-unsecure-$it", "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/bypass-unsecure/bypass-unsecure-$it.txt") })
                }),
                ConfigCategory("По протоколам (split-by-protocols/)", listOf(
                    "vless-secure", "vmess-secure", "trojan-secure", "ss-secure", "ssr-secure", "tuic-secure", "hysteria-secure", "hysteria2-secure", "hy2-secure",
                    "vless", "vmess", "trojan", "ss", "ssr", "tuic", "hysteria", "hysteria2", "hy2"
                ).map { ConfigSource(it, "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/split-by-protocols/$it.txt") })
            )
        ),
        ConfigProvider(
            id = "avencores",
            name = "Goida VPN (AvenCores)",
            owner = "AvenCores",
            repo = "goida-vpn-configs",
            categories = listOf(
                ConfigCategory("Основные конфиги", listOf(
                    ConfigSource("1", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/1.txt", "sakha1370/OpenRay"),
                    ConfigSource("2", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/2.txt", "sevcator/5ubscpt10n"),
                    ConfigSource("3", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/3.txt", "yitong2333/proxy-minging"),
                    ConfigSource("4", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/4.txt", "acymz/AutoVPN"),
                    ConfigSource("5", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/5.txt", "miladtahanian/V2RayCFGDumper"),
                    ConfigSource("6", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/6.txt", "roosterkid/openproxylist"),
                    ConfigSource("7", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/7.txt", "Epodonios/v2ray-configs"),
                    ConfigSource("8", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/8.txt", "CidVpn/cid-vpn-config"),
                    ConfigSource("9", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/9.txt", "mohamadfg-dev/telegram-v2ray-configs-collector"),
                    ConfigSource("10", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/10.txt", "mheidari98/.proxy"),
                    ConfigSource("11", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/11.txt", "youfoundamin/V2rayCollector"),
                    ConfigSource("12", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/12.txt", "VOID-Anonymity/V.O.I.D-VPN_Bypass"),
                    ConfigSource("13", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/13.txt", "MahsaNetConfigTopic/config"),
                    ConfigSource("14", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/14.txt", "LalatinaHub/Mineral"),
                    ConfigSource("15", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/15.txt", "miladtahanian/Config-Collector"),
                    ConfigSource("16", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/16.txt", "Pawdroid/Free-servers"),
                    ConfigSource("17", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/17.txt", "MhdiTaheri/V2rayCollector_Py"),
                    ConfigSource("18", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/18.txt", "yebekhe/TelegramV2rayCollector"),
                    ConfigSource("19", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/19.txt", "V2rayCollector/V2rayCollector"),
                    ConfigSource("20", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/20.txt", "Zizif0/V2Ray-Config"),
                    ConfigSource("21", "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/21.txt", "mahdibland/V2RayAggregator")
                ))
            )
        )
    )

    val byeDpiStrategies = listOf(
        "-d3 -r7 -a1", "-d1 -s2 -a1", "-d1+s -d2+s -r7", "-d1+s -d5+s -a1",
        "-d1 -s4 -s6 -a1", "-d1 -r25+s -a1", "-d1 -s2+s -a1", "-d1 -f -1 -s -a1",
        "-d1 -a1 -At,r,s -d1 -a1", "-d1+s -a2 -a5 -r5 -a1", "-f3 -s2 -s7 -q4+s -a1",
        "-d4+s -q4+hm -a2 -a1", "-f -1 -o6 -t7 -d5 -m2", "--fake -1 --ttl 8 --split 1+s --disorder 3+s -a1",
        "-n \"google.com\" -Qr -d1 -f -1", "-d1 -s1+s -r1+s -f -1 -d1 -a1",
        "-d1 -a1 -An -f1+nme -t5 -a1", "-n \"google.com\" -Qr -f -1 -r1+s -a1",
        "-n \"google.com\" -Qr -df3 -f -1 -a1", "-d1 -d2+s -s -a1 -d2 -m4 -a1",
        "-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -d24+s -s30+s -a1",
        "-n \"google.com\" -Qr -f209 -s1+sm -f1-5 -a1", "-o3+s -r9+s -Qr -n \"google.com\" -s -a1",
        "-f -1 -f4 -n \"google.com\" -s7+s -a1", "-d1 -d1 -r1+s -s -d1+s -d3+s -a1",
        "-d1 -r1+s -f -1 -s -d3 -d3+s -a1", "-q3+s -s27+s -s3+s -f -1 -s -a1",
        "-n \"google.com\" -Qr -m2 -f -1 -df -a1", "-s25 -r5+s -s25+s -a1 -At,r,s -s50 -r5+s -s50+s -a1",
        "-a1 -q1 -a1 -At,c,s -f -1 -r1+s -a1", "-d1 -d1 -a1 -At,c,s -f -1 -r1+s -a1",
        "-s1 -q1 -s -a1 -At,c,s -f -1 -r1+s -a1", "-s1 -o1 -s -a1 -At,c,s -f -1 -r1+s -a1",
        "-s1 -d1 -s -a1 -At,c,s -f -1 -r1+s -a1", "-n \"google.com\" -Qr -d5+sm -s5+sm -o2 -t4 -a1",
        "-o1 -a1 -At,r,s -f -1 -a1 -Ar,s -o1 -a1 -At -r1+s -f -1 -t6 -a1",
        "-s1 -d1 -r1+s -a1 -Ar -o1 -a1 -At -f -1 -r1+s -a1",
        "-s1 -q1 -r1+s -a1 -Ar -o1 -a1 -At -f -1 -r1+s -a1",
        "-s1 -o1 -r1+s -a1 -Ar -o1 -a1 -At -f -1 -r1+s -a1",
        "-d1 -o1 -Ar -q1 -a1 -At -f -1 -r1+s -a1", "-q1 -o1 -Ar -o1 -a1 -At -f -1 -r1+s -a1",
        "-d1 -s4 -d3 -s1+s -d1+s -s10+s -d13+s -a1",
        "-d1 -s1+s -d1+s -s3+s -d6+s -s12+s -d14+s -s20+s -d24+s -s30+s -a1",
        "-d1 -s1+s -d5+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
        "-d1 -o1 -a1 -Ar -d1 -a1 -At -f -1 -r1+s -a1", "-d1 -a1 -a1 -Ar -o1 -a1 -At -f -1 -r1+s -a1",
        "-a2 -O4 -a1 -q1 -a2 -Ar -a5 -a1+s -f1+s -r20+s -a2",
        "-f -1 -n \"google.com\" -Qr -a2+s -s -a28 -t4 -a1",
        "-n \"google.com\" -Qr -f6+nm -d2 -df1 -f1+nm -a3 -r1 -a1",
        "-q1 -d1 -d1 -q1 -Ar -a1 -a1 -Ar -f -1 -r1+s -a1",
        "-f1 -t5 -n \"google.com\" -q5+h -Qr -f2 -q1 -r1+s -t15 -q1 -o2 -a1",
        "-n \"google.com\" -d2:5+h -f -3 -r2+sm -a2 -c50+s -r2+s -f -4 -a1",
        "-r -1+s -a20+sm -x3:7+sm -d5:5+sm -f300+s -Qr -Y -f -1 -a1",
        "-f -1 -Qr -s1+sm -d5+s -s5+sm -o2 -a1 -As -r1+s -d6+s -a1",
        "-o1 -r -5+se -a1 -At,r,s -d1 -n \"google.com\" -Qr -f -1 -a1",
        "-d1 -s1 -q1 -Y -a1 -Ar -s5 -o1+s -d5+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
        "-s1 -q1 -a1 -Y -At -a1 -s -f -1 -r1+s -a1 -As -d1+s -o1 -s29+s -a1",
        "-s1 -o1 -a1 -Y -Ar -s5 -o1+s -a1 -At -f -1 -r1+s -a1 -As -s1 -o1+s -s -1 -a1",
        "-s1 -d1 -a1 -Y -Ar -d5 -o1+s -a1 -At -f -1 -r1+s -a1 -As -d1 -o1+s -s -1 -a1",
        "-s1 -q1 -a1 -Y -Ar -a1 -s5 -o2 -At -f -1 -r1+s -a1 -As -s1 -o1+s -s -1 -a1",
        "-s1 -q1 -a1 -Ar -s5 -o1+s -a1 -At -f -1 -d1+s -a1 -As -s1 -o1+s -s -1 -a1",
        "-s1 -q1 -a1 -Ar -s5 -o2 -a1 -At -f -1 -r1+s -a1 -As -s1 -o1+s -s -1 -a1",
        "-d14+s -q29+s -d25+s -t5 -a1 -At,c,s -r14h -a1",
        "-d14+s -q29+s -s33+s -d14+s -s26+s -f -1 -s -a1",
        "-d14+s -q29+s -s28+s -t5 -a1 -At,c,s -r14h -a1",
        "-d1 -s14+s -f1+s -a1 -At -q1+s -t1 -r2 -a1",
        "-f1+nme -t6 -a1 -As -n \"google.com\" -Qr -s1:4+sm -a1 -As -s5:12+sm -a1 -As -d5 -q7 -r5 -Mh -a1",
        "-d1+s -s50+s -a1 -As -f20 -r2+s -a1 -At -d2 -s1+s -s5+s -s10+s -s15+s -s25+s -s35+s -s50+s -s60+s -a1",
        "-n \"google.com\" -Qr -f -204 -s1:5+sm -a1 -As -d1 -s3+s -s5+s -q7 -a1 -As -o2 -f-43 -a1 -As -r5 -Mh -s1:5+s -s3:7+sm -a1",
        "-n \"google.com\" -Qr -f -205 -a1 -As -s1:5+sm -a1 -As -s2:8+sm -a1 -As -d5 -q7 -o2 -f -43 -f -85 -f -165 -r5 -Mh -a1",
        "-o1 -a1 -At,r,s -f -1 -a1 -At,r,s -d1:11+sm -s -a1 -At,r,s -n \"google.com\" -Qr -f1 -d1:11+sm -s1:11+sm -s -a1",
        "-o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -s -a1 -As -s1 -d1 -s5+s -s10+s -s15+s -s20+s -s -a1",
        "-d1 -d5+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -s -a1 -As -d1 -d5+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -s -a1",
        "-q2 -x2 -x3+s -r3 -s4 -r4 -x5+s -c5+s -s6 -s7+s -r8 -s9+s -Qr -Mh,d,r -a1 -At,r -s2+s -r2 -d2 -s3 -r3 -r4 -s4 -d5+s -r5 -d6 -s7+s -d7 -a1",
        "-f -200 -Qr -s3:5+sm -a1 -As -d1 -s6+sm -s8+sh -f -300 -d6+sh -a1 -At,r,s -o2 -f -30 -As -r5 -Mh -r6+sh -f -250 -s2:7+s -s3:6+sm -a1 -At,r,s -s3:5+sm -s6+s -s7:9+s -s30+sm -a1",
        "-Ku -L\"\\x05\\x00\\x00\\x06\\xe1\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00*\" -a3 -An -f64+se -n \"google.com\" -t5"
    )
}
