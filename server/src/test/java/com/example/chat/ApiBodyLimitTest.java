package com.example.chat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class ApiBodyLimitTest {
    @Test void unknownLengthStreamIsStillBounded() throws Exception {
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/auth/login") {
            @Override public long getContentLengthLong(){return -1;}
            @Override public int getContentLength(){return -1;}
        };
        request.setContent(new byte[ApiBodyLimit.MAX_BYTES+1024]);
        var filter=new ApiBodyLimit();
        assertThatThrownBy(()->filter.doFilter(request,new MockHttpServletResponse(),(req,res)->{
            ((HttpServletRequest)req).getInputStream().readAllBytes();
        })).isInstanceOf(ApiBodyLimit.TooLarge.class);
    }
    @Test void validBytesPassWithoutModification() throws Exception {
        var request=new MockHttpServletRequest("PUT","/api/keys");byte[] body="{\"preKeys\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);request.setContent(body);
        new ApiBodyLimit().doFilter(request,new MockHttpServletResponse(),(req,res)->assertThat(((HttpServletRequest)req).getInputStream().readAllBytes()).isEqualTo(body));
    }
}
