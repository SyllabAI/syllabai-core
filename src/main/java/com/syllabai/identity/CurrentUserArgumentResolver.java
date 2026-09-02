package com.syllabai.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@code @CurrentUserId UUID learnerId} parameters from the request attribute
 * populated by {@code JwtAuthenticationFilter}.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    static final String USER_ID_ATTRIBUTE = "com.syllabai.userId";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class)
                && UUID.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                   ModelAndViewContainer mavContainer,
                                   NativeWebRequest webRequest,
                                   WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        Object userId = request == null ? null : request.getAttribute(USER_ID_ATTRIBUTE);
        if (userId instanceof UUID id) {
            return id;
        }
        throw new IllegalStateException("no authenticated user id on request");
    }
}
