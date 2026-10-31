// omni-client is the small process boundary between Omni's Android lifecycle
// and the pinned NoizDNS mobile library. It intentionally exposes only the
// options represented by an imported profile; it is not a VPN simulator.
package main

import (
	"flag"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"noizdns/mobile"
)

func main() {
	var dohURL, dotAddr, tcpAddr, udpAddr string
	var publicKey, utlsDistribution, resolverMode, protectAddress string
	var authoritative, noiz, stealth bool
	var maxPayload, edns0Size, rrSpread int

	flag.StringVar(&dohURL, "doh", "", "DNS-over-HTTPS resolver URL")
	flag.StringVar(&dotAddr, "dot", "", "DNS-over-TLS resolver(s), comma-separated")
	flag.StringVar(&tcpAddr, "tcp", "", "plain TCP DNS resolver(s), comma-separated")
	flag.StringVar(&udpAddr, "udp", "", "UDP DNS resolver(s), comma-separated")
	flag.StringVar(&publicKey, "pubkey", "", "server Noise public key (64 hex characters)")
	flag.StringVar(&utlsDistribution, "utls", "", "uTLS fingerprint distribution")
	flag.StringVar(&resolverMode, "resolver-mode", "fanout", "fanout or roundrobin")
	flag.StringVar(&protectAddress, "fd-control-unix-socket", "", "Android FD-protection endpoint")
	flag.BoolVar(&authoritative, "authoritative", false, "authoritative-resolver tuning")
	flag.BoolVar(&noiz, "noiz", false, "enable NoizDNS mode")
	flag.BoolVar(&stealth, "stealth", false, "enable NoizDNS stealth mode")
	flag.IntVar(&maxPayload, "max-payload", 0, "DNS payload cap; zero uses the default")
	flag.IntVar(&edns0Size, "edns0-size", 0, "EDNS(0) UDP payload size; zero uses the default")
	flag.IntVar(&rrSpread, "rr-spread", 3, "round-robin resolver spread")
	flag.Parse()

	if flag.NArg() != 2 {
		log.Fatalf("usage: omni-client [options] DOMAIN LOCALADDR:LOCALPORT")
	}
	transports := 0
	for _, value := range []string{dohURL, dotAddr, tcpAddr, udpAddr} {
		if value != "" {
			transports++
		}
	}
	if transports != 1 {
		log.Fatalf("exactly one of -doh, -dot, -tcp, or -udp is required")
	}
	if publicKey == "" {
		log.Fatalf("-pubkey is required")
	}
	if stealth && !noiz {
		log.Fatalf("-stealth requires -noiz")
	}
	if rrSpread < 1 || rrSpread > 5 {
		log.Fatalf("-rr-spread must be between 1 and 5")
	}

	var dnsAddr string
	switch {
	case dohURL != "":
		dnsAddr = dohURL
	case dotAddr != "":
		dnsAddr = prefixEndpoints(dotAddr, "tls://")
	case tcpAddr != "":
		dnsAddr = prefixEndpoints(tcpAddr, "tcp://")
	case udpAddr != "":
		dnsAddr = udpAddr
	}

	client, err := mobile.NewClient(dnsAddr, flag.Arg(0), publicKey, flag.Arg(1))
	if err != nil {
		log.Fatalf("create client: %v", err)
	}
	if utlsDistribution != "" {
		client.SetUTLSFingerprint(utlsDistribution)
	}
	client.SetAuthoritativeMode(authoritative)
	client.SetNoizMode(noiz)
	if stealth {
		client.SetStealthMode(true)
	}
	if maxPayload > 0 {
		client.SetMaxPayload(maxPayload)
	}
	if edns0Size > 0 {
		client.SetEDNS0Size(edns0Size)
	}
	client.SetResolverMode(resolverMode)
	client.SetRRSpreadCount(int64(rrSpread))
	if username := os.Getenv("OMNI_DNSTT_SOCKS_USER"); username != "" {
		client.SetSocksCredentials(username, os.Getenv("OMNI_DNSTT_SOCKS_PASSWORD"))
	}
	mobile.SetFDControlUnixSocket(protectAddress)

	if err := client.Start(); err != nil {
		log.Fatalf("start tunnel: %v", err)
	}
	log.Printf("NoizDNS process started (noiz=%t authoritative=%t)", noiz, authoritative)

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	defer signal.Stop(sigCh)
	health := time.NewTicker(500 * time.Millisecond)
	defer health.Stop()
	for {
		select {
		case <-sigCh:
			done := make(chan struct{})
			go func() {
				client.Stop()
				close(done)
			}()
			select {
			case <-done:
			case <-time.After(5 * time.Second):
				log.Printf("NoizDNS stop timed out; exiting")
			}
			return
		case <-health.C:
			if !client.IsRunning() {
				// The mobile API runs the tunnel in a goroutine. Do not leave a
				// seemingly alive Android process after that goroutine has failed.
				log.Printf("NoizDNS tunnel stopped unexpectedly")
				os.Exit(1)
			}
		}
	}
}

func prefixEndpoints(raw, prefix string) string {
	parts := strings.Split(raw, ",")
	for i, part := range parts {
		part = strings.TrimSpace(part)
		if !strings.HasPrefix(part, prefix) {
			part = prefix + part
		}
		parts[i] = part
	}
	return strings.Join(parts, ",")
}
