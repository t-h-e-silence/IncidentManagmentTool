package org.example.controller;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.example.common.exception.UnauthenticatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Turns the {@value IncidentManagementHttpController#USER_HEADER} header (user id or username) into the user id
 * for {@link ActingUser} parameters. Missing or unknown → {@link UnauthenticatedException} (401). Whether the user
 * may act is checked by the controller, as for any caller.
 */
@Component
public class ActingUserResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ActingUserResolver.class);

    private final IncidentManagementController controller;

    public ActingUserResolver(IncidentManagementController controller) {
        this.controller = controller;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(ActingUser.class) && parameter.getParameterType() == UUID.class;
    }

    @Override
    public UUID resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                NativeWebRequest request, WebDataBinderFactory binderFactory) {
        String user = request.getHeader(IncidentManagementHttpController.USER_HEADER);
        if (user == null || user.isBlank()) {
            log.warn("{} {}: header {} is missing", method(request), path(request),
                    IncidentManagementHttpController.USER_HEADER);
            throw new UnauthenticatedException("Header " + IncidentManagementHttpController.USER_HEADER
                    + " (user id or username) is missing");
        }
        UUID userId = controller.findUserId(user).orElseThrow(() -> {
            log.warn("{} {}: no user with username '{}'. Demo users (ada, alice, bob, carol, dan, erin) exist only "
                    + "when the app was started with the 'seed' profile", method(request), path(request), user);
            return new UnauthenticatedException("Unknown user " + user);
        });
        log.debug("{} {}: {} '{}' is user {}", method(request), path(request),
                IncidentManagementHttpController.USER_HEADER, user, userId);
        return userId;
    }

    private static String method(NativeWebRequest request) {
        HttpServletRequest http = request.getNativeRequest(HttpServletRequest.class);
        return http == null ? "?" : http.getMethod();
    }

    private static String path(NativeWebRequest request) {
        HttpServletRequest http = request.getNativeRequest(HttpServletRequest.class);
        return http == null ? "?" : http.getRequestURI();
    }
}
