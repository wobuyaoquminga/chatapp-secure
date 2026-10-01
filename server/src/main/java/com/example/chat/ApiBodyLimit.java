package com.example.chat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds JSON before Jackson allocates it, including unknown-length/chunked bodies. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+20)
public class ApiBodyLimit extends OncePerRequestFilter {
    static final int MAX_BYTES=512*1024;
    static final class TooLarge extends IOException { TooLarge(){super("Request body limit exceeded");} }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        if (!request.getRequestURI().startsWith("/api/") || !java.util.Set.of("POST","PUT","PATCH").contains(request.getMethod())) {
            chain.doFilter(request,response);return;
        }
        if (request.getContentLengthLong()>MAX_BYTES) { reject(response);return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private ServletInputStream stream;
            @Override public ServletInputStream getInputStream() throws IOException {
                if (stream!=null) return stream;
                ServletInputStream input=super.getInputStream();
                stream=new ServletInputStream() {
                    private long count;
                    private void record(int n) throws TooLarge { if(n>0 && (count+=n)>MAX_BYTES)throw new TooLarge(); }
                    @Override public int read() throws IOException {int value=input.read();record(value<0?0:1);return value;}
                    @Override public int read(byte[] bytes,int offset,int length) throws IOException {int n=input.read(bytes,offset,length);record(n);return n;}
                    @Override public boolean isFinished(){return input.isFinished();}
                    @Override public boolean isReady(){return input.isReady();}
                    @Override public void setReadListener(ReadListener listener){input.setReadListener(listener);}
                    @Override public void close() throws IOException {input.close();}
                };
                return stream;
            }
            @Override public BufferedReader getReader() throws IOException {return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        },response);
    }
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);response.setCharacterEncoding("UTF-8");response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"请求内容超出大小限制\"}");
    }
}
