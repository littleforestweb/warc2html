/*
 * Copyright 2021 National Library of Australia
 * SPDX-License-Identifier: Apache-2.0
 */
package org.netpreserve.warc2html;

import net.htmlparser.jericho.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.regex.Pattern;

public class LinkRewriter {

    private static final Pattern CSS_URL_PATTERN = Pattern.compile("(?<=[\\s:]url\\()\\s*([^ \"')]+|\"[^\"]+\"|'[^']+')\\s*(?=\\))");
    private static final Pattern JS_URL_PATTERN = Pattern.compile("href=\\\"(.*?)\\\"");

    static String rewriteCSS(String css, Function<String, String> urlMapping) {
        return CSS_URL_PATTERN.matcher(css).replaceAll(match -> {
            String url = match.group(1);
            if (url.startsWith("\"") || url.startsWith("'")) {
                url = url.substring(1, url.length() - 1);
            }
            String replacement = urlMapping.apply(url);
            if (replacement == null || url.equals(replacement)) {
                return match.group();
            }
            return replacement.replaceAll("([\"')])", "\\$1");
        });
    }

    static String rewriteJS(String js, Function<String, String> urlMapping, String rndStr) {
        return JS_URL_PATTERN.matcher(js).replaceAll(match -> {
            String url = match.group(1);

            if (!(url.startsWith("/"))) {
                return "href=\"" + url + "\"";
            }

            if (url.startsWith("\"") || url.startsWith("'")) {
                url = url.substring(1, url.length() - 1);
            }

            String replacement = urlMapping.apply(url);
            replacement = "href=\"' + " + rndStr + "_relativePath + '" + replacement + "\"";
            return replacement;
        });
    }

    public static long rewriteHTML(InputStream input, OutputStream output, Function<String, String> urlMapping) throws IOException {
        Source source = new Source(input);
        OutputDocument outputDocument = new OutputDocument(source);
        long linksRewritten = 0;
        source.fullSequentialParse();

        for (var el : source.getAllElements(HTMLElementName.STYLE)) {
            String css = el.getContent().toString();
            String rewritten = rewriteCSS(css, urlMapping);
            if (rewritten != null && !css.equals(rewritten)) {
                outputDocument.replace(el.getContent(), rewritten);
            }
        }

        for (var tag : source.getAllStartTags()) {

            ArrayList<Attribute> URIAttrs = new ArrayList<>();
            if (tag.toString().startsWith("<area") || tag.toString().startsWith("<a")) {
                for (var attr : tag.getAttributes()) {
                    if (attr.hasValue() && attr.toString().startsWith("data-src")) {
                        URIAttrs.add(attr);
                    }
                }
            }
            URIAttrs.addAll(tag.getURIAttributes());

            for (var attr : URIAttrs) {
                if (!attr.hasValue() || attr.toString().startsWith("usemap")) {
                    continue;
                }

                String url = attr.getValue();
                url = url.replaceAll(" ", "%20");
                url = url.replaceAll("\\[", "%5B");
                url = url.replaceAll("\\]", "%5D");

                String rewritten = urlMapping.apply(url);
                if (url.startsWith("#") || rewritten == null || rewritten.equals(url)) {
                    continue;
                }

                String replacement = "\"" + CharacterReference.encode(rewritten, true) + "\"";
                outputDocument.replace(attr.getValueSegmentIncludingQuotes(), replacement);
                linksRewritten++;
            }
        }

        String encoding = source.getEncoding();
        if (encoding == null) {
            encoding = "iso-8859-1"; // seems to be what jericho defaults to for reading
        }
        outputDocument.writeTo(new OutputStreamWriter(output, encoding));

        return linksRewritten;
    }
}
