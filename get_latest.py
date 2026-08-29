import ipaddress
import json
import socket
import urllib.request
from urllib.parse import urlparse

MAVEN_SEARCH_URL = "https://search.maven.org/solrsearch/select?q=a:ffmpeg-kit-audio"


def assert_public_https(url: str) -> None:
    """SSRF 防护：仅允许 https，且目标 host 不得指向本机/内网/保留地址。"""
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise ValueError(f"仅允许 https URL: {url}")
    host = parsed.hostname
    if host == "localhost" or host.endswith(".localhost") or host.endswith(".local"):
        raise ValueError(f"拒绝内网主机: {host}")
    for info in socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP):
        ip = ipaddress.ip_address(info[4][0])
        if (
            ip.is_loopback
            or ip.is_private
            or ip.is_link_local
            or ip.is_reserved
            or ip.is_multicast
            or ip.is_unspecified
        ):
            raise ValueError(f"目标解析到保留/内网地址 {ip}，拒绝请求")


def main() -> None:
    assert_public_https(MAVEN_SEARCH_URL)
    request = urllib.request.Request(
        MAVEN_SEARCH_URL,
        headers={"User-Agent": "transfromed-version-check/1.1"},
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        data = json.loads(response.read())
    for doc in data["response"]["docs"]:
        print(f"{doc['g']}:{doc['a']}:{doc['latestVersion']}")


if __name__ == "__main__":
    main()
