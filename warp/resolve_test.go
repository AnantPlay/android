package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

// withSources runs a test with the given address sources and a TLS config that trusts the test server.
func withSources(t *testing.T, srv *httptest.Server, s ...source) {
	oldS, oldT := sources, apiTLS
	t.Cleanup(func() { sources, apiTLS = oldS, oldT })
	sources = s
	pool := x509.NewCertPool()
	pool.AddCert(srv.Certificate())
	apiTLS = func(host string) *tls.Config { return &tls.Config{ServerName: "example.com", RootCAs: pool} }
}

func fixed(ips ...string) func(context.Context, string) ([]string, error) {
	return func(context.Context, string) ([]string, error) { return ips, nil }
}

func hang(ctx context.Context, _ string) ([]string, error) { <-ctx.Done(); return nil, ctx.Err() }

func server(t *testing.T) (*httptest.Server, string) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) }))
	t.Cleanup(srv.Close)
	_, port, _ := net.SplitHostPort(strings.TrimPrefix(srv.URL, "https://"))
	return srv, port
}

// DNS that never answers (blocked) must not hold up an address that is known.
func TestBlockedDNSDoesNotWait(t *testing.T) {
	srv, port := server(t)
	withSources(t, srv, source{"blocked", hang}, source{"also blocked", hang}, source{"built-in", fixed("127.0.0.1")})
	start := time.Now()
	c, err := dialAPI(context.Background(), "tcp", "example.com:"+port)
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	if d := time.Since(start); d > 2*time.Second {
		t.Fatalf("took %v", d)
	}
}

// A poisoned answer (nothing listening, or something that is not the real server) is skipped for the one that works.
func TestPoisonedAnswersSkipped(t *testing.T) {
	srv, port := server(t)
	bogus, _ := net.Listen("tcp", "127.0.0.2:"+port) // accepts, then hangs up: not a TLS server
	if bogus != nil {
		defer bogus.Close()
		go func() {
			for {
				c, err := bogus.Accept()
				if err != nil {
					return
				}
				c.Close()
			}
		}()
	}
	withSources(t, srv, source{"poisoned", fixed("127.0.0.2", "127.0.0.3")}, source{"real", fixed("127.0.0.1")})
	c, err := dialAPI(context.Background(), "tcp", "example.com:"+port)
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
}

// Nothing found, or nothing reachable: an error that says why, not a hang.
func TestAllFailSaysWhy(t *testing.T) {
	srv, port := server(t)
	withSources(t, srv, source{"none", func(context.Context, string) ([]string, error) { return nil, context.DeadlineExceeded }})
	_, err := dialAPI(context.Background(), "tcp", "example.com:"+port)
	if err == nil || !strings.Contains(err.Error(), "no address found") {
		t.Fatalf("got %v", err)
	}
	withSources(t, srv, source{"dead", fixed("127.0.0.9")})
	_, err = dialAPI(context.Background(), "tcp", "example.com:1")
	if err == nil || !strings.Contains(err.Error(), "127.0.0.9") {
		t.Fatalf("got %v", err)
	}
}

func TestParseDoH(t *testing.T) {
	ips, err := parseDoH([]byte(`{"Status":0,"Answer":[{"type":5,"data":"x.example."},{"type":1,"data":"104.16.24.84"},{"type":1,"data":"104.16.192.82"}]}`))
	if err != nil || len(ips) != 2 || ips[0] != "104.16.24.84" {
		t.Fatal(ips, err)
	}
	if _, err := parseDoH([]byte(`{"Status":3}`)); err == nil {
		t.Fatal("NXDOMAIN must be an error")
	}
	if _, err := parseDoH([]byte(`<html>blocked</html>`)); err == nil {
		t.Fatal("a block page must be an error")
	}
}

func TestAddDNS(t *testing.T) {
	old := sources
	t.Cleanup(func() { sources = old })
	addDNS(" 87.101.204.72, not-an-ip ,85.194.70.174")
	if len(sources) != len(old)+2 || !strings.Contains(sources[0].name, "87.101.204.72") || !strings.Contains(sources[1].name, "85.194.70.174") {
		t.Fatal(sources[0].name, len(sources))
	}
}

// Real network, run by hand: WARP_LIVE=1 go test -run Live -v   (prints what each source finds for the sign-up name)
func TestLiveSources(t *testing.T) {
	if os.Getenv("WARP_LIVE") == "" {
		t.Skip("set WARP_LIVE=1")
	}
	addDNS(os.Getenv("WARP_DNS"))
	for _, s := range sources {
		ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
		start := time.Now()
		ips, err := s.find(ctx, apiHost)
		cancel()
		t.Logf("%-28s %v %v (%d ms)", s.name, ips, err, time.Since(start).Milliseconds())
	}
}
