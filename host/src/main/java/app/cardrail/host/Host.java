package app.cardrail.host;

import app.cardrail.acquirer.AcquirerApplication;
import app.cardrail.closedloop.MeridianApplication;
import app.cardrail.demoshop.DemoShopApplication;
import app.cardrail.gateway.GatewayApplication;
import app.cardrail.issuer.IssuerApplication;
import app.cardrail.network.NetworkApplication;
import app.cardrail.trace.TraceApplication;
import app.cardrail.vault.VaultApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Runs a group of services in one JVM, each in its own Spring context with its own config, port and schema
 * (docs/decisions.md, ADR-005). Which group comes from the first argument:
 *
 * <ul>
 *   <li>{@code acquiring}: the acquirer, the vault, the gateway, and the demo shop that pays through it</li>
 *   <li>{@code network}: the trace service, Railnet, and Meridian (the closed loop; ADR-005 accepts the shared JVM)</li>
 *   <li>{@code issuing}: Kestrel Bank</li>
 *   <li>{@code cell}: all of them, as the other cell's standby</li>
 * </ul>
 *
 * Services start in dependency order, but none needs another to be up: links connect when first used.
 */
public final class Host {

    private static final Logger log = LoggerFactory.getLogger(Host.class);

    static final Map<String, List<Supplier<SpringApplicationBuilder>>> GROUPS = Map.of(
            "issuing", List.of(IssuerApplication::builder),
            "network", List.of(TraceApplication::builder, NetworkApplication::builder, MeridianApplication::builder),
            "acquiring", List.of(AcquirerApplication::builder, VaultApplication::builder, GatewayApplication::builder,
                    DemoShopApplication::builder),
            "cell", List.of(TraceApplication::builder, IssuerApplication::builder, NetworkApplication::builder, MeridianApplication::builder,
                    AcquirerApplication::builder, VaultApplication::builder, GatewayApplication::builder,
                    DemoShopApplication::builder));

    private Host() {
    }

    public static void main(String[] args) {
        String group = args.length > 0 ? args[0] : System.getenv().getOrDefault("CARDRAIL_HOST", "");
        if (!GROUPS.containsKey(group)) {
            System.err.println("Usage: java -jar cardrail-host.jar <acquiring|network|issuing|cell>");
            System.exit(2);
        }
        String[] rest = args.length > 0 ? Arrays.copyOfRange(args, 1, args.length) : args;
        try {
            start(group, rest);
        } catch (RuntimeException e) {
            log.error("The {} host couldn't start; exiting so it gets restarted", group, e);
            System.exit(1);
        }
    }

    public static List<ConfigurableApplicationContext> start(String group, String... args) {
        List<ConfigurableApplicationContext> started = new ArrayList<>();
        for (Supplier<SpringApplicationBuilder> service : GROUPS.get(group)) {
            started.add(service.get().properties("cardrail.host=" + group).run(args));
        }
        log.info("The {} host is up: {} services in one JVM", group, started.size());
        return started;
    }
}
