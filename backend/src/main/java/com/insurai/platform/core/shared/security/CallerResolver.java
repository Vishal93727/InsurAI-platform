package com.insurai.platform.core.shared.security;

import java.util.Locale;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Turns the bearer token into a {@link Caller} for any controller parameter of that type.
 *
 * <p>Registered by {@link WebConfig} rather than scanned as a component: a {@code HandlerMethodArgumentResolver}
 * bean is picked up by every {@code @WebMvcTest} slice in the platform, including other modules' slices that
 * have no {@link RoleLookup}.
 *
 * <p>Role resolution, in order:
 * <ol>
 *   <li>the staff table - an explicit assignment by a manager always wins;</li>
 *   <li>for tokens issued by this platform only, the {@code role} claim: ADMIN becomes MANAGER, HR becomes
 *       ASSESSOR, AGENT stays AGENT. Tokens from any other issuer never get a role from their claims;</li>
 *   <li>otherwise CUSTOMER.</li>
 * </ol>
 */
public class CallerResolver implements HandlerMethodArgumentResolver {

    private final RoleLookup roles;
    private final String platformIssuer;

    public CallerResolver(RoleLookup roles, String platformIssuer) {
        this.roles = roles;
        this.platformIssuer = platformIssuer;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Caller.class;
    }

    @Override
    public Caller resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                  WebDataBinderFactory binders) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt) || jwt.getSubject() == null) {
            throw new IllegalStateException("no authenticated subject on a /v1 request");
        }
        String subject = jwt.getSubject();
        return new Caller(subject, roles.staffRole(subject).orElseGet(() -> platformRole(jwt)));
    }

    private Role platformRole(Jwt jwt) {
        if (!platformIssuer.equals(jwt.getClaimAsString("iss"))) {
            return Role.CUSTOMER;
        }
        String claim = jwt.getClaimAsString("role");
        if (claim == null) {
            return Role.CUSTOMER;
        }
        return switch (claim.trim().toUpperCase(Locale.ROOT)) {
            case "ADMIN" -> Role.MANAGER;
            case "HR" -> Role.ASSESSOR;
            case "AGENT" -> Role.AGENT;
            default -> Role.CUSTOMER;
        };
    }
}
