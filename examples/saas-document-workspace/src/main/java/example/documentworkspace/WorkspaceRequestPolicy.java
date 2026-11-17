// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.documentworkspace;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
/** Retains strict form envelopes and security headers around generated MVC routes. */
@Configuration(proxyBeanMethods=false)
class WorkspaceRequestPolicy implements WebMvcConfigurer {
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                WorkspaceController.headers().forEach((key,values)->values.forEach(value->response.addHeader(key,value)));
                switch(request.getServletPath()) {
                    case "/workspace" -> {if(request.getQueryString()!=null)throw new IllegalArgumentException();}
                    case "/documents/create" -> WorkspaceController.fields(request,"title","text");
                    case "/documents/delete" -> WorkspaceController.fields(request,"id");
                }
                return true;
            }
        }).addPathPatterns("/workspace","/documents/**");
    }
}
