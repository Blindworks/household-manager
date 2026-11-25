package com.household.manager.communicator;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Verzeichnis aller REST-Endpunkte, aus den Spring-Mappings des laufenden Systems gelesen. Ein neues Feature
 * mit Endpunkt steht nach dem Deploy von selbst darin; hier wird nie ein einzelnes Feature eingetragen.
 *
 * <p>Nicht aufgenommen werden Zugangsdaten (Pfadsegmente und Body-Felder), Medien und Datenstroeme sowie der
 * Communicator selbst. Ueber Rechte entscheidet nicht dieses Verzeichnis, sondern beim Aufruf die SecurityConfig.
 */
@Component
@Slf4j
public class ApiCatalog {

    private static final String OWN_PACKAGE = "com.household.manager";
    private static final int SCHEMA_DEPTH = 3;
    private static final Set<String> EXCLUDED_SEGMENTS = Set.of(
            "auth", "login", "logout", "password", "service-tokens", "communicator", "management");
    /** Nur einfache Pfade: nichts, womit sich Kodierung, Query oder Verzeichniswechsel einschleusen liesse. */
    private static final Pattern SAFE_PATH = Pattern.compile("/[\\p{L}\\p{N}/_.:@ -]+");

    private final ObjectProvider<RequestMappingHandlerMapping> mapping;
    private final ObjectMapper objectMapper;
    private volatile List<Endpoint> endpoints;
    private volatile String index;

    public ApiCatalog(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mapping,
                      ObjectMapper objectMapper) {
        this.mapping = mapping;
        this.objectMapper = objectMapper;
    }

    record Param(String name, String type, boolean required) {
    }

    record Endpoint(String method, PathPattern pattern, String controller, String operation,
                    List<Param> query, JavaType body, JavaType response) {

        String path() {
            return pattern.getPatternString();
        }

        boolean reads() {
            return "GET".equals(method);
        }
    }

    List<Endpoint> endpoints() {
        List<Endpoint> current = endpoints;
        if (current == null) {
            current = build(mapping.getObject().getHandlerMethods());
            endpoints = current;
            log.info("API-Verzeichnis: {} Endpunkte", current.size());
        }
        return current;
    }

    /** Kompakte Liste fuer den Prompt, nach Controller gruppiert; aendert sich zur Laufzeit nie (Prompt-Cache). */
    public String index() {
        String current = index;
        if (current == null) {
            Map<String, List<Endpoint>> groups = endpoints().stream()
                    .collect(Collectors.groupingBy(Endpoint::controller, TreeMap::new, Collectors.toList()));
            StringBuilder text = new StringBuilder();
            groups.forEach((controller, list) -> {
                text.append("# ").append(controller).append('\n');
                list.forEach(endpoint -> text.append(indexLine(endpoint)).append('\n'));
            });
            current = text.toString();
            index = current;
        }
        return current;
    }

    /** Findet den Endpunkt zu einem konkreten Pfad; das speziellste Muster gewinnt wie bei Spring selbst. */
    public Optional<Endpoint> find(String method, String path) {
        if (path == null || !SAFE_PATH.matcher(path).matches() || path.contains("..") || path.contains("//")
                || isExcludedPath(path)) {
            return Optional.empty();
        }
        PathContainer container = PathContainer.parsePath(path);
        return endpoints().stream()
                .filter(endpoint -> endpoint.method().equals(method) && endpoint.pattern().matches(container))
                .min(Comparator.comparing(Endpoint::pattern, PathPattern.SPECIFICITY_COMPARATOR));
    }

    /** Parameter, Body und Antwort mit Typen; {@code path} darf ein Muster oder ein konkreter Pfad sein. */
    public String describe(String method, String path) {
        Endpoint endpoint = endpoints().stream()
                .filter(e -> e.method().equals(method) && e.path().equals(path)).findFirst()
                .or(() -> find(method, path))
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Endpunkt: " + method + " " + path));
        String query = endpoint.query().stream()
                .map(p -> p.name() + ": " + p.type() + (p.required() ? " (Pflicht)" : ""))
                .collect(Collectors.joining(", "));
        return endpoint.method() + " " + endpoint.path()
                + "\nQuery: " + (query.isEmpty() ? "keine" : query)
                + "\nBody: " + (endpoint.body() == null ? "keiner" : schema(endpoint.body(), SCHEMA_DEPTH))
                + "\nAntwort: " + schema(endpoint.response(), SCHEMA_DEPTH);
    }

    // --- Aufbau -----------------------------------------------------------------------------

    List<Endpoint> build(Map<RequestMappingInfo, HandlerMethod> handlerMethods) {
        List<Endpoint> result = new ArrayList<>();
        handlerMethods.forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith(OWN_PACKAGE)) {
                return;
            }
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                for (String pattern : info.getPatternValues()) {
                    Endpoint endpoint = toEndpoint(method.name(), pattern, handler);
                    if (isOffered(endpoint, handler)) {
                        result.add(endpoint);
                    }
                }
            }
        });
        result.sort(Comparator.comparing(Endpoint::path).thenComparing(Endpoint::method));
        return List.copyOf(result);
    }

    private Endpoint toEndpoint(String method, String pattern, HandlerMethod handler) {
        List<Param> query = new ArrayList<>();
        JavaType body = null;
        for (MethodParameter parameter : handler.getMethodParameters()) {
            RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
            if (requestParam != null) {
                query.add(toParam(parameter, requestParam));
            } else if (parameter.hasParameterAnnotation(RequestBody.class)) {
                body = javaType(parameter.getGenericParameterType());
            }
        }
        return new Endpoint(method, PathPatternParser.defaultInstance.parse(pattern),
                handler.getBeanType().getSimpleName().replace("Controller", ""), handler.getMethod().getName(),
                List.copyOf(query), body, unwrap(javaType(handler.getMethod().getGenericReturnType())));
    }

    private Param toParam(MethodParameter parameter, RequestParam annotation) {
        Parameter reflected = parameter.getExecutable().getParameters()[parameter.getParameterIndex()];
        String name = !annotation.name().isEmpty() ? annotation.name() : reflected.getName();
        boolean optional = !annotation.required() || parameter.isOptional()
                || !ValueConstants.DEFAULT_NONE.equals(annotation.defaultValue());
        return new Param(name, schema(unwrap(javaType(parameter.getGenericParameterType())), 0), !optional);
    }

    private boolean isOffered(Endpoint endpoint, HandlerMethod handler) {
        boolean upload = Arrays.stream(handler.getMethod().getParameterTypes())
                .anyMatch(type -> MultipartFile.class.isAssignableFrom(type)
                        || (type.isArray() && MultipartFile.class.isAssignableFrom(type.getComponentType())));
        Class<?> response = endpoint.response().getRawClass();
        boolean media = response == byte[].class || Resource.class.isAssignableFrom(response)
                || ResponseBodyEmitter.class.isAssignableFrom(response)
                || StreamingResponseBody.class.isAssignableFrom(response);
        return !upload && !media && !isExcludedPath(endpoint.path())
                && endpoint.query().stream().noneMatch(p -> SecretFields.isSecret(p.name()))
                && (endpoint.body() == null || !containsSecret(endpoint.body(), SCHEMA_DEPTH));
    }

    private static boolean isExcludedPath(String path) {
        return Arrays.stream(path.toLowerCase().split("/")).anyMatch(EXCLUDED_SEGMENTS::contains);
    }

    private boolean containsSecret(JavaType type, int depth) {
        if (type.isContainerType()) {
            return type.getContentType() != null && containsSecret(type.getContentType(), depth);
        }
        if (depth == 0 || !isOwn(type)) {
            return false;
        }
        return properties(type).stream().anyMatch(property -> SecretFields.isSecret(property.getName())
                || containsSecret(property.getPrimaryType(), depth - 1));
    }

    // --- Darstellung ------------------------------------------------------------------------

    private String indexLine(Endpoint endpoint) {
        String query = endpoint.query().isEmpty() ? "" : "?" + endpoint.query().stream()
                .map(p -> p.name() + (p.required() ? "!" : "")).collect(Collectors.joining("&"));
        String line = endpoint.method() + " " + endpoint.path() + query + " " + endpoint.operation();
        String fields = endpoint.reads() ? schema(endpoint.response(), 1)
                : endpoint.body() == null ? "" : "body " + schema(endpoint.body(), 1);
        return fields.isEmpty() || "void".equals(fields) ? line : line + " -> " + fields;
    }

    /** Tiefe 1 nennt nur Feldnamen, ab 2 auch Typen. */
    private String schema(JavaType type, int depth) {
        JavaType unwrapped = unwrap(type);
        if (unwrapped.isMapLikeType()) {
            return "{*: " + schema(unwrapped.getContentType(), depth) + "}";
        }
        if (unwrapped.isContainerType()) {
            return "[" + schema(unwrapped.getContentType(), depth) + "]";
        }
        if (unwrapped.isEnumType()) {
            return Arrays.stream(unwrapped.getRawClass().getEnumConstants()).map(String::valueOf)
                    .collect(Collectors.joining("|"));
        }
        if (depth == 0 || !isOwn(unwrapped)) {
            return unwrapped.getRawClass() == Void.class ? "void" : unwrapped.getRawClass().getSimpleName();
        }
        return properties(unwrapped).stream()
                .map(p -> depth == 1 ? p.getName() : p.getName() + ": " + schema(p.getPrimaryType(), depth - 1))
                .collect(Collectors.joining(depth == 1 ? "," : ", ", "{", "}"));
    }

    private List<BeanPropertyDefinition> properties(JavaType type) {
        return objectMapper.getSerializationConfig().introspect(type).findProperties();
    }

    private static boolean isOwn(JavaType type) {
        return type.getRawClass().getName().startsWith(OWN_PACKAGE) && !type.isEnumType();
    }

    private JavaType javaType(Type type) {
        return objectMapper.getTypeFactory().constructType(type);
    }

    private JavaType unwrap(JavaType type) {
        boolean wrapper = HttpEntity.class.isAssignableFrom(type.getRawClass())
                || Optional.class.isAssignableFrom(type.getRawClass());
        if (!wrapper) {
            return type;
        }
        JavaType inner = type.getContentType() != null ? type.getContentType()
                : type.containedTypeCount() > 0 ? type.containedType(0) : null;
        return inner == null ? javaType(Object.class) : unwrap(inner);
    }
}
