package organization.web;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link ActingUser} resolver. Being a {@link WebMvcConfigurer}, it is also active in
 * {@code @WebMvcTest} slices, like {@link HttpErrorHandler}.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ObjectProvider<ActingUserLookup> lookup;

    public WebConfig(ObjectProvider<ActingUserLookup> lookup) {
        this.lookup = lookup;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ActingUserResolver(lookup));
    }
}
