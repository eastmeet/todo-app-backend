package simple.todo.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.common.util.StringUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.json.JSONObject;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpReqResLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_HEADER_REFERER = "referer";
    public static final String REQUEST_HEADER_ACCEPT = "accept";
    public static final String REQUEST_HEADER_USER_AGENT = "user-agent";

    public static final String EXCEPT_STRING_FOR_SECURITY_PASSWORD = "password";
    public static final String EXCEPT_STRING_FOR_SECURITY_TOKEN = "token";
    public static final String REPLACEMENT_STRING_FOR_SECURITY = "*****";

    // JSON Pretty Print를 위한 ObjectMapper
    private final ObjectMapper prettyObjectMapper;

    public HttpReqResLoggingFilter() {
        this.prettyObjectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    /**
     * Http Logging Filter
     *
     * @param request     HttpServletRequest
     * @param response    HttpServletResponse
     * @param filterChain FilterChain
     * @throws ServletException ServletException
     * @throws IOException      IOException
     */
    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (isExcludeUrl(request, response, filterChain)) {
            return;
        }

        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        long start = System.currentTimeMillis();
        filterChain.doFilter(requestWrapper, responseWrapper);
        double processTime = (System.currentTimeMillis() - start) / 1000.0;
        String decodeURL = URLDecoder.decode(request.getRequestURI(), StandardCharsets.UTF_8);

        try {
            // Pretty formatted 로그 메시지 생성
            StringBuilder logMessage = new StringBuilder();
            logMessage.append("##### HTTP Logging #####\n");
            logMessage.append("[REQUEST]\n");
            logMessage.append("  • API: (").append(request.getMethod()).append(") ").append(decodeURL).append("\n");

            // Headers를 JSON으로 pretty print
            Map<String, String> headers = getHeaders(request);
            if (!headers.isEmpty()) {
                logMessage.append("  • Headers:\n").append(toPrettyJson(headers)).append("\n");
            }

            logMessage.append("  • Request IP: ").append(getClientIP(request)).append("\n");

            // Request Params
            String queryString = requestWrapper.getQueryString();
            if (queryString != null) {
                logMessage.append("  • Request Params: ").append(queryString).append("\n");
            } else {
                logMessage.append("  • Request Params: null\n");
            }

            // Request Body를 JSON으로 pretty print
            String requestBody = getRequestBody(requestWrapper);
            if (requestBody != null && !requestBody.trim().isEmpty()) {
                logMessage.append("  • Request Body:\n").append(toPrettyJson(requestBody)).append("\n");
            } else {
                logMessage.append("  • Request Body: null\n");
            }

            logMessage.append("[RESPONSE]\n");

            // Response Body를 JSON으로 pretty print
            String responseBody = getResponseBody(responseWrapper, request);
            if (responseBody != null && !responseBody.trim().isEmpty()) {
                logMessage.append("  • Response Body:\n").append(toPrettyJson(responseBody)).append("\n");
            } else {
                logMessage.append("  • Response Body: null\n");
            }

            logMessage.append("[RESULT]\n");
            logMessage.append("  • status: ").append(responseWrapper.getStatus()).append("\n");
            logMessage.append("  • process time: ").append(String.format("%.3fs", processTime));

            log.info(logMessage.toString());

        } catch (Exception e) {
            log.error("HTTP Logging failed. (API: ({}) {})", request.getMethod(),
                request.getRequestURI());
        } finally {
            responseWrapper.copyBodyToResponse();
        }
    }

    /**
     * JSON 문자열을 Pretty format으로 변환
     *
     * @param jsonString JSON 문자열 또는 일반 문자열
     * @return Pretty formatted JSON 문자열
     */
    private String toPrettyJson(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return "null";
        }

        try {
            JsonNode jsonNode = prettyObjectMapper.readTree(jsonString);
            return prettyObjectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(jsonNode);
        } catch (Exception e) {
            return jsonString;
        }
    }

    /**
     * Map 객체를 Pretty JSON으로 변환
     *
     * @param map Map 객체
     * @return Pretty formatted JSON 문자열
     */
    private String toPrettyJson(Map<String, String> map) {
        try {
            return prettyObjectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return map.toString();
        }
    }

    private String getClientIP(HttpServletRequest request) {
        String[] headers = {
            "X-Forwarded-For",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_CLIENT_IP",
            "HTTP_X_FORWARDED_FOR",
            "X-Real-IP"
        };

        for (String header : headers) {
            String ip = request.getHeader(header);
            if (isValidIP(ip)) {
                return ip.split(",")[0].trim();
            }
        }

        return request.getRemoteAddr();
    }

    private boolean isValidIP(String ip) {
        return ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip);
    }

    /**
     * Request Header 추출
     *
     * @param request HttpServletRequest
     * @return Request header의 referer, accept, user-agent
     */
    private Map<String, String> getHeaders(HttpServletRequest request) {
        Enumeration<String> headerArray = request.getHeaderNames();
        Map<String, String> headerMap = new HashMap<>();
        String headerName = null;
        while (headerArray.hasMoreElements()) {
            headerName = headerArray.nextElement();
            if (headerName.equals(REQUEST_HEADER_REFERER)
                || headerName.equals(REQUEST_HEADER_ACCEPT)
                || headerName.equals(REQUEST_HEADER_USER_AGENT)) {
                headerMap.put(headerName, request.getHeader(headerName));
            }
        }
        return headerMap;
    }

    /**
     * RequestBody 추출
     *
     * @param request ContentCachingRequestWrapper
     * @return RequestBody 문자열
     */
    private String getRequestBody(ContentCachingRequestWrapper request) {
        String payload = null;
        ContentCachingRequestWrapper wrapper = WebUtils.getNativeRequest(request,
            ContentCachingRequestWrapper.class);
        if (ObjectUtils.isNotEmpty(wrapper) && wrapper.getContentLength() > 0) {
            payload = new String(wrapper.getContentAsByteArray());
        }
        return convertPayloadForSecurity(payload);
    }

    /**
     * ResponseBody 추출
     *
     * @param response ContentCachingResponseWrapper
     * @return ResponseBody 문자열
     * @throws IOException
     */
    private String getResponseBody(ContentCachingResponseWrapper response,
        HttpServletRequest request) {
        String payload = null;
        ContentCachingResponseWrapper wrapper = WebUtils.getNativeResponse(response,
            ContentCachingResponseWrapper.class);

        if (ObjectUtils.isNotEmpty(wrapper) && wrapper.getContentSize() > 0) {
            payload = new String(wrapper.getContentAsByteArray());

            // copyBodyToResponse()는 doFilterInternal에서 처리하므로 여기서는 제거
            String contentType = wrapper.getContentType();
            if (contentType != null && contentType.startsWith("image/")) {
                String[] pathSegments = URLDecoder.decode(request.getRequestURI(),
                    StandardCharsets.UTF_8).split("/");
                return pathSegments[pathSegments.length - 1];
            }
        }

        return convertPayloadForSecurity(payload);
    }

    /**
     * Request/Response Body에 보안을 위한 정보를 "*****"로 변환
     *
     * @param payload Request/Response Body
     * @return 변환 문자열
     */
    private String convertPayloadForSecurity(String payload) {
        if (StringUtils.isNotEmpty(payload) && Boolean.TRUE.equals(isJsonObjectType(payload))) {
            JSONObject jsonObject = new JSONObject(payload);

            Iterator<String> iterator = jsonObject.keys();
            while (iterator.hasNext()) {
                String key = iterator.next();
                if (key.contains(EXCEPT_STRING_FOR_SECURITY_PASSWORD)
                    || key.contains(EXCEPT_STRING_FOR_SECURITY_TOKEN)) {
                    jsonObject.put(key, REPLACEMENT_STRING_FOR_SECURITY);
                }
            }
            payload = jsonObject.toString();
        }
        return payload;
    }

    public Boolean isJsonObjectType(String jsonString) {
        try {
            new JSONObject(jsonString);
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private boolean isExcludeUrl(HttpServletRequest request, HttpServletResponse response,
        FilterChain filterChain) throws IOException, ServletException {
        Pattern pattern = Pattern.compile("/v3/api-docs|/swagger|/monitor/health|/favicon.ico");
        Matcher matcher = pattern.matcher(request.getRequestURI());

        if (matcher.find()) {
            filterChain.doFilter(request, response);
            return true;
        }
        return false;
    }
}