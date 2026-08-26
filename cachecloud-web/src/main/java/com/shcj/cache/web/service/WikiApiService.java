package com.shcj.cache.web.service;

import com.shcj.cache.web.controller.api.dto.WikiContentDto;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.parser.ParserEmulationProfile;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.builder.Extension;
import com.vladsch.flexmark.util.options.MutableDataSet;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

@Service
public class WikiApiService {

    public WikiContentDto getClientAccessDoc() throws Exception {
        WikiContentDto dto = new WikiContentDto();
        dto.setHtml(markdown2html("access/client", ".md"));
        dto.setToc(markdown2html("access/client", ".toc.md"));
        return dto;
    }

    private String markdown2html(String filename, String suffix) throws Exception {
        String templatePath = "static/wiki/" + filename + suffix;
        InputStream inputStream = WikiApiService.class.getClassLoader().getResourceAsStream(templatePath);
        if (inputStream == null) {
            return null;
        }
        String markdown = new String(read(inputStream), Charset.forName("utf-8"));
        MutableDataSet options = new MutableDataSet();
        options.setFrom(ParserEmulationProfile.MARKDOWN);
        options.set(Parser.EXTENSIONS, Arrays.asList(new Extension[]{TablesExtension.create()}));
        Document document = Parser.builder(options).build().parse(markdown);
        return HtmlRenderer.builder(options).build().render(document);
    }

    private byte[] read(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[1024];
        int len;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            while ((len = inputStream.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        } finally {
            inputStream.close();
            bos.close();
        }
    }
}
