package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"tokenworks-mobile/services/remote/internal/accounts"
	"tokenworks-mobile/services/remote/internal/control"
	"tokenworks-mobile/services/remote/internal/httpapi"
	"tokenworks-mobile/services/remote/internal/relay"
	"tokenworks-mobile/services/remote/internal/requestlog"
	"tokenworks-mobile/services/remote/internal/store"
)

func main() {
	operation := run
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "version", "--version":
			operation = func() error { return runVersionCommand(os.Args[2:], os.Stdout) }
		case "identities":
			operation = func() error { return runIdentityCommand(os.Args[2:], os.Stdout) }
		case "app-releases":
			operation = func() error { return runAppReleaseCommand(os.Args[2:], os.Stdout) }
		default:
			operation = func() error { return errors.New("remote service arguments are invalid") }
		}
	}
	if err := operation(); err != nil {
		log.Print(err)
		os.Exit(1)
	}
}

func run() error {
	nodeID := strings.TrimSpace(os.Getenv("REMOTE_RELAY_NODE_ID"))
	relayConfig, err := loadRelayDeployment(os.Getenv("REMOTE_RELAY_CONFIG_FILE"), nodeID)
	if err != nil {
		return err
	}
	database, err := store.Open(context.Background(), os.Getenv("REMOTE_DATABASE_URL"))
	if err != nil {
		return errors.New("remote service database is unavailable or insecurely configured")
	}
	defer database.Close()
	recovery, err := database.NewRecoveryGuard(context.Background(), os.Getenv("REMOTE_DATABASE_EPOCH"))
	if err != nil {
		return errors.New("remote service database recovery is unavailable or deployment epoch does not match")
	}
	verifier, proofs, err := loadIdentity(database, recovery)
	if err != nil {
		return err
	}
	admissions := store.DefaultSerializableAdmissions
	if configured := strings.TrimSpace(os.Getenv("REMOTE_SERIALIZABLE_ADMISSIONS")); configured != "" {
		parsed, parseErr := strconv.Atoi(configured)
		if parseErr != nil {
			return errors.New("remote serializable admission budget is invalid")
		}
		admissions = parsed
	}
	if err := database.ConfigureSerializableAdmissions(admissions); err != nil {
		return errors.New("remote serializable admission budget must be between 1 and 30")
	}
	apiConfig := httpapi.Config{HostClientIDs: csv(os.Getenv("REMOTE_HOST_CLIENT_IDS"))}
	apiConfig.Releases, err = loadRegisteredAppReleases(context.Background(), os.Getenv("REMOTE_APP_RELEASES_FILE"), database, recovery.Epoch())
	if err != nil {
		return err
	}
	consumer, err := database.NewControlConsumer(context.Background(), nodeID)
	if err != nil {
		return errors.New("remote control consumer is unavailable or already active")
	}
	defer func() {
		release, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := consumer.Release(release); err != nil {
			log.Print("remote control consumer release unavailable=true")
		}
	}()
	origin := ""
	if relayConfig != nil {
		origin = relayConfig.Origin
	}
	directory, err := consumer.NewRelayDirectory(context.Background(), origin)
	if err != nil {
		return errors.New("remote relay directory is unavailable")
	}
	handler := httpapi.New(directory, verifier, apiConfig)
	routingConfig := relay.RoutingConfig{Directory: directory}
	if relayConfig != nil {
		routingConfig.ClientTLS, routingConfig.MaxProxyConnections = relayConfig.clientTLS, relayConfig.MaxProxyConnections
	}
	if err := handler.ConfigureRouting(routingConfig); err != nil {
		return errRelayConfiguration
	}
	accessLog := slog.New(slog.NewJSONHandler(os.Stderr, nil))
	privateEndpoint, err := startRelayEndpoint(relayConfig,
		requestlog.Middleware(recovery.Middleware(handler.PrivateRelayHandler(handler.ForwardedDeviceHandler())), "relay", accessLog))
	if err != nil {
		return err
	}
	defer privateEndpoint.close()
	// Fence upgraded transports if any listener exits unexpectedly.
	defer func() {
		drain, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = handler.Drain(drain)
	}()
	var publicHandler http.Handler = handler
	if origin := os.Getenv("REMOTE_ACCOUNTS_ORIGIN"); origin != "" {
		accountService, accountErr := accounts.New(database, recovery.Epoch())
		if accountErr != nil {
			return accountErr
		}
		portal, accountErr := accounts.NewPortal(accountService, origin, os.Getenv("REMOTE_ACCOUNTS_REGISTRATION") == "true")
		if accountErr != nil {
			return errors.New("remote account origin configuration is invalid")
		}
		publicHandler = portal.Handler(handler)
	}
	server := &http.Server{
		Addr:              envOr("REMOTE_LISTEN_ADDR", "127.0.0.1:8080"),
		Handler:           requestlog.Middleware(recovery.Middleware(publicHandler), "public", accessLog),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       0,
		WriteTimeout:      0,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    32 << 10,
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	controlCtx, stopControl := context.WithCancel(ctx)
	controlDone := make(chan struct{})
	controlFailure := make(chan error, 1)
	recoveryDone := make(chan struct{})
	recoveryFailure := make(chan error, 1)
	go func() {
		defer close(recoveryDone)
		if err := recovery.Run(controlCtx); err != nil {
			recoveryFailure <- err
		}
	}()
	proofsDone := make(chan struct{})
	go func() {
		defer close(proofsDone)
		if proofs != nil {
			proofs.Run(controlCtx)
		}
	}()
	go func() {
		defer close(controlDone)
		if err := control.RunConsumer(controlCtx, consumer, handler); err != nil {
			controlFailure <- err
		}
	}()
	defer func() {
		stopControl()
		<-controlDone
		<-proofsDone
		<-recoveryDone
	}()
	result := make(chan error, 1)
	go func() { result <- server.ListenAndServe() }()
	select {
	case <-ctx.Done():
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		drained := make(chan error, 1)
		go func() { drained <- handler.Drain(shutdown) }()
		shutdownErr := server.Shutdown(shutdown)
		drainErr := <-drained
		if shutdownErr != nil {
			_ = server.Close()
			return fmt.Errorf("remote service shutdown failed: %w", shutdownErr)
		}
		if drainErr != nil {
			_ = server.Close()
			return fmt.Errorf("remote native drain failed: %w", drainErr)
		}
		err := <-result
		if !errors.Is(err, http.ErrServerClosed) {
			return fmt.Errorf("remote service failed before shutdown: %w", err)
		}
		return nil
	case err := <-result:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return fmt.Errorf("remote service listener failed: %w", err)
	case <-controlFailure:
		_ = server.Close()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := handler.Drain(shutdown); err != nil {
			return fmt.Errorf("remote control consumer failed and native drain failed: %w", err)
		}
		return errors.New("remote control consumer authority unavailable")
	case <-privateEndpoint.errors():
		_ = server.Close()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := handler.Drain(shutdown); err != nil {
			return fmt.Errorf("remote private relay failed and native drain failed: %w", err)
		}
		return errors.New("remote private relay listener failed")
	case <-recoveryFailure:
		_ = server.Close()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := handler.Drain(shutdown); err != nil {
			return fmt.Errorf("remote recovery fence failed and native drain failed: %w", err)
		}
		return errors.New("remote service database recovery authority unavailable")
	}
}

func csv(value string) []string {
	parts := strings.Split(value, ",")
	items := make([]string, 0, len(parts))
	for _, part := range parts {
		if item := strings.TrimSpace(part); item != "" {
			items = append(items, item)
		}
	}
	return items
}

func envOr(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}
