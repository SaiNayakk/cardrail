package app.cardrail.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * What every service imports: problems, request ids, the internal key, and how to call another service.
 * Imported explicitly ({@code @Import(CommonWeb.class)}) so services sharing a JVM host never scan each other.
 */
@Configuration(proxyBeanMethods = false)
@Import(ProblemHandler.class)
public class CommonWeb {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        var registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    FilterRegistrationBean<InternalKeyFilter> internalKeyFilter(@Value("${cardrail.internal-key}") String key) {
        var registration = new FilterRegistrationBean<>(new InternalKeyFilter(key));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    /** A client for another party's internal API: short timeouts, the internal key, the request id carried on. */
    public static RestClient internalClient(String baseUrl, String internalKey, Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(InternalKeyFilter.HEADER, internalKey)
                .requestInterceptor((request, body, execution) -> {
                    String id = org.slf4j.MDC.get(RequestIdFilter.MDC_KEY);
                    if (id != null) {
                        request.getHeaders().set(RequestIdFilter.HEADER, id);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
