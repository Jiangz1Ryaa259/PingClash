// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	C "github.com/metacubex/mihomo/constant"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// PRIVATE bridge: only the selected provider is fetched, through its declared
// outbound. No source URL, header, body or raw transport error is a public error.
func FetchRemoteProvider(text, bodiesJSON, id, userAgent string, timeoutMillis int) (result string) {
	result = remoteError(errors.New("provider_invalid"))
	defer func() {
		if recover() != nil {
			result = remoteError(errors.New("provider_invalid"))
		}
	}()
	root, specs, e := remotePlan(text)
	if e != nil {
		return remoteError(e)
	}
	var selected *remoteSpec
	for i := range specs {
		if specs[i].ID == id {
			selected = &specs[i]
			break
		}
	}
	if selected == nil || selected.Mode == "inline" || timeoutMillis < 1 || timeoutMillis > 30000 || len(userAgent) > 256 || hasControl(userAgent) {
		return result
	}
	spec := *selected
	fail := func(code string) string { return remoteError(&remoteFailure{spec.Index, spec.Kind, code}) }
	if spec.Mode == "file" {
		return fail("provider_local_file_required")
	}
	if spec.DownloadProxy == "" || spec.DownloadProxy == "DIRECT" {
		return fail("provider_invalid")
	}
	if len(bodiesJSON) > maxProviderBridgeBytes {
		return fail("provider_payload")
	}
	var bodies map[string]string
	if json.Unmarshal([]byte(bodiesJSON), &bodies) != nil {
		return fail("provider_payload")
	}
	proxy, e := providerBootstrap(root, specs, bodies, spec.DownloadProxy)
	if e != nil {
		return fail("provider_proxy_unavailable")
	}
	defer proxy.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMillis)*time.Millisecond)
	defer cancel()
	transport := providerHTTPTransport(proxy)
	defer transport.CloseIdleConnections()
	body, code := fetchProviderBody(ctx, transport, spec, userAgent)
	if code != "" {
		return fail(code)
	}
	content := string(body)
	if spec.Format == "mrs" {
		content = mrsBodyPrefix + base64.StdEncoding.EncodeToString(body)
	} else if !utf8.Valid(body) {
		return fail("provider_payload")
	}
	return remoteResult(map[string]any{"state": "passed", "content": content})
}

func providerBootstrap(root map[string]any, specs []remoteSpec, bodies map[string]string, target string) (C.ProxyAdapter, error) {
	// Resolve already available proxy sources only. Missing sources remain invalid
	// members, never DIRECT. The caller retries after other sources are downloaded.
	partial := map[string]any{}
	for _, key := range []string{"proxies", "proxy-groups", "proxy-providers", "pingclash-selections"} {
		if v, ok := root[key]; ok {
			partial[key] = v
		}
	}
	ready := map[string]string{}
	for key, value := range bodies {
		ready[key] = value
	}
	for _, spec := range specs {
		if spec.Kind == "proxy" && spec.Mode != "inline" {
			if _, ok := ready[spec.ID]; !ok {
				ready[spec.ID] = `proxies: [{name: unavailable-provider, type: socks5, server: 127.0.0.1, port: 0}]`
			}
		}
	}
	text, _ := json.Marshal(partial)
	encoded, _ := json.Marshal(ready)
	var reply struct{ State, Content string }
	if json.Unmarshal([]byte(ResolveRemoteProviders(string(text), string(encoded))), &reply) != nil || reply.State != "passed" {
		return nil, errors.New("provider_proxy_unavailable")
	}
	graph, e := parseLocalDefinition(reply.Content)
	if e != nil {
		return nil, e
	}
	return graph.runtime(target)
}

func fetchProviderBody(ctx context.Context, transport http.RoundTripper, spec remoteSpec, userAgent string) ([]byte, string) {
	headers := http.Header{}
	headers.Set("User-Agent", userAgent)
	for key, value := range spec.Headers {
		headers.Set(key, value)
	}
	client := &http.Client{Transport: transport, CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) > 5 || !validRemoteURL(req.URL.String()) {
			return errProviderRedirect
		}
		from := via[len(via)-1].URL
		port := func(p string) string {
			if p == "" {
				return "443"
			}
			return p
		}
		if !strings.EqualFold(from.Hostname(), req.URL.Hostname()) || port(from.Port()) != port(req.URL.Port()) {
			safe := http.Header{}
			for _, key := range []string{"User-Agent", "Accept", "Accept-Language"} {
				if value := req.Header.Values(key); len(value) > 0 {
					safe[key] = value
				}
			}
			req.Header = safe
		}
		return nil
	}}
	request, e := http.NewRequestWithContext(ctx, http.MethodGet, spec.URL, nil)
	if e != nil {
		return nil, "provider_invalid"
	}
	request.Header = headers
	response, e := client.Do(request)
	if e != nil {
		var unknown x509.UnknownAuthorityError
		var hostname x509.HostnameError
		var invalid x509.CertificateInvalidError
		var network net.Error
		if errors.As(e, &unknown) || errors.As(e, &hostname) || errors.As(e, &invalid) {
			return nil, "provider_tls"
		}
		if errors.Is(e, context.DeadlineExceeded) || errors.As(e, &network) && network.Timeout() {
			return nil, "provider_timeout"
		}
		if errors.Is(e, errProviderRedirect) {
			return nil, "provider_redirect"
		}
		return nil, "provider_network"
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, "provider_http_" + strconv.Itoa(response.StatusCode)
	}
	limit := 2 * 1024 * 1024
	if spec.Kind == "rule" {
		limit = maxRuleProviderBytes
	}
	code := "provider_size_limit"
	if spec.SizeLimit > 0 && spec.SizeLimit <= limit {
		limit = spec.SizeLimit
		code = "provider_declared_size_limit"
	}
	if response.ContentLength > int64(limit) {
		return nil, code
	}
	body, e := io.ReadAll(io.LimitReader(response.Body, int64(limit)+1))
	if len(body) > limit {
		return nil, code
	}
	if e != nil {
		return nil, "provider_network"
	}
	return body, ""
}

func validRemoteURL(raw string) bool {
	u, e := url.Parse(raw)
	if e != nil || len(raw) > 4096 || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Fragment != "" || hasControl(raw) {
		return false
	}
	if u.Port() != "" {
		port, e := strconv.Atoi(u.Port())
		if e != nil || port < 1 || port > 65535 {
			return false
		}
	}
	return true
}

func providerHTTPTransport(proxy C.ProxyAdapter) *http.Transport {
	return &http.Transport{Proxy: nil, TLSHandshakeTimeout: 8 * time.Second, ResponseHeaderTimeout: 15 * time.Second, MaxResponseHeaderBytes: 16 * 1024,
		DialContext: func(ctx context.Context, _, address string) (net.Conn, error) {
			metadata := &C.Metadata{NetWork: C.TCP, Type: C.INNER}
			if metadata.SetRemoteAddress(address) != nil {
				return nil, errors.New("provider_network")
			}
			p, e := flowProxy(ctx, proxy, metadata)
			if e != nil {
				return nil, e
			}
			return p.DialContext(ctx, metadata)
		}}
}

var errProviderRedirect = errors.New("provider_redirect")
