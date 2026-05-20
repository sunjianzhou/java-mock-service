package com.mock.core.protocol;

import com.mock.core.config.EndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.server.ServerWebExchange;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import reactor.core.publisher.Mono;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * XML/SOAP 协议适配器，匹配组代中心 Nacao 系列接口（text/xml）。
 * 使用 DOM 解析（namespace-unaware）提取 SOAP Body 中所有叶子元素的文本内容。
 */
public class XmlAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(XmlAdapter.class);
    private static final javax.xml.parsers.DocumentBuilderFactory DB_FACTORY;

    static {
        DB_FACTORY = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        DB_FACTORY.setNamespaceAware(false);
        try {
            DB_FACTORY.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DB_FACTORY.setFeature("http://xml.org/sax/features/external-general-entities", false);
            DB_FACTORY.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DB_FACTORY.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (javax.xml.parsers.ParserConfigurationException ignored) {
        }
    }

    @Override
    public boolean supports(EndpointConfig config) {
        String ct = config.getContentType();
        return ct != null && (ct.startsWith("text/xml") || ct.startsWith("application/xml"));
    }

    @Override
    public Mono<Map<String, String>> extractParams(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
            .map(dataBuffer -> {
                try {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    String body = new String(bytes, StandardCharsets.UTF_8);
                    if (body.trim().isEmpty()) {
                        return Collections.<String, String>emptyMap();
                    }
                    return parseXmlToParams(body);
                } finally {
                    DataBufferUtils.release(dataBuffer);
                }
            })
            .onErrorResume(e -> {
                log.warn("Failed to parse XML body: {}", e.getMessage());
                return Mono.just(Collections.<String, String>emptyMap());
            })
            .defaultIfEmpty(Collections.emptyMap());
    }

    Map<String, String> parseXmlToParams(String xml) {
        Map<String, String> params = new LinkedHashMap<>();
        try {
            Document doc = DB_FACTORY.newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));
            collectLeafElements(doc.getDocumentElement(), params);
            log.debug("Extracted {} XML params", params.size());
        } catch (Exception e) {
            log.warn("Failed to parse XML body: {}", e.getMessage());
        }
        return params;
    }

    private void collectLeafElements(Element element, Map<String, String> params) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element childElement = (Element) child;
                String text = childElement.getTextContent();
                if (text != null && !text.trim().isEmpty() && !hasElementChildren(childElement)) {
                    params.put(childElement.getTagName(), text.trim());
                }
                collectLeafElements(childElement, params);
            }
        }
    }

    private boolean hasElementChildren(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }
}
