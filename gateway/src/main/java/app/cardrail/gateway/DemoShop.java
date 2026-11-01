package app.cardrail.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * In the demo, makes sure the demo shop is a merchant, with the keys the demo shop was given. Signing it up needs
 * the acquirer, which may still be starting, so it keeps trying in the background.
 */
@Component
class DemoShop implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoShop.class);

    private final GatewayProperties props;
    private final Merchants merchants;

    DemoShop(GatewayProperties props, Merchants merchants) {
        this.props = props;
        this.merchants = merchants;
    }

    @Override
    public void run(ApplicationArguments args) {
        var demo = props.demo();
        if (demo == null || !demo.enabled()) {
            return;
        }
        if (demo.secretKey() == null || !demo.secretKey().startsWith("sk_") || demo.publishableKey() == null
                || !demo.publishableKey().startsWith("pk_")) {
            log.warn("The demo is on but the demo shop's keys aren't set; it can't take payments");
            return;
        }
        Thread.ofPlatform().name("demo-shop-signup").daemon().start(() -> {
            for (int attempt = 1; ; attempt++) {
                try {
                    String webhookSecret = demo.webhookSecret() == null || demo.webhookSecret().isBlank() ? null : demo.webhookSecret();
                    if (!merchants.secretKeyExists(demo.secretKey())) {
                        merchants.create("Railpay demo shop", "5999", "San Francisco", demo.webhookUrl(), demo.secretKey(),
                                demo.publishableKey(), webhookSecret);
                        log.info("Signed up the demo shop");
                    } else if (webhookSecret != null) {
                        // a shop signed up before webhooks existed, or whose settings changed
                        merchants.setWebhook(demo.secretKey(), demo.webhookUrl(), webhookSecret);
                    }
                    return;
                } catch (RuntimeException e) {
                    if (attempt % 10 == 1) {
                        log.info("Can't sign up the demo shop yet ({}); trying again", e.getMessage());
                    }
                    try {
                        Thread.sleep(3_000);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
            }
        });
    }
}
