package organization.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import organization.web.exception.UnauthenticatedException;

/**
 * Turns the {@value ActingUser#HEADER} header (user id or username) into the user id for {@link ActingUser}
 * parameters, using the service's {@link ActingUserLookup}. Missing or unknown → {@link UnauthenticatedException}
 * (401). Whether the user may act is checked by the endpoint, as for any caller.
 */
public class ActingUserResolver implements HandlerMethodArgumentResolver {

    private static final Logger log = LoggerFactory.getLogger(ActingUserResolver.class);

    private final ObjectProvider<ActingUserLookup> lookup;

    public ActingUserResolver(ObjectProvider<ActingUserLookup> lookup) {
        this.lookup = lookup;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(ActingUser.class) && parameter.getParameterType() == UUID.class;
    }

    @Override
    public UUID resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                NativeWebRequest request, WebDataBinderFactory binderFactory) {
        String user = request.getHeader(ActingUser.HEADER);
        if (user == null || user.isBlank()) {
            log.warn("{} {}: header {} is missing", method(request), path(request), ActingUser.HEADER);
            throw new UnauthenticatedException("Header " + ActingUser.HEADER + " (user id or username) is missing");
        }
        UUID userId = lookup.getObject().findUserId(user).orElseThrow(() -> {
            log.warn("{} {}: no user with username '{}'. The demo users are ada, alice, bob, carol, dan and erin",
                    method(request), path(request), user);
            return new UnauthenticatedException("Unknown user " + user);
        });
        log.debug("{} {}: {} '{}' is user {}", method(request), path(request), ActingUser.HEADER, user, userId);
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
