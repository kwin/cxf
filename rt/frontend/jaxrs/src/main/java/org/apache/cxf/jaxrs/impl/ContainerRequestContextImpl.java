/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.jaxrs.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.helpers.IOUtils;
import org.apache.cxf.io.DelegatingInputStream;
import org.apache.cxf.jaxrs.utils.ExceptionUtils;
import org.apache.cxf.jaxrs.utils.HttpUtils;
import org.apache.cxf.message.Message;

public class ContainerRequestContextImpl extends AbstractRequestContextImpl
    implements ContainerRequestContext {

    private static final String ENDPOINT_ADDRESS_PROPERTY = "org.apache.cxf.transport.endpoint.address";
    private static final String ENDPOINT_URI_PROPERTY = "org.apache.cxf.transport.endpoint.uri";

    private boolean preMatch;
    public ContainerRequestContextImpl(Message message, boolean preMatch, boolean responseContext) {
        super(message, responseContext);
        this.preMatch = preMatch;
    }

    @Override
    public InputStream getEntityStream() {
        return m.getContent(InputStream.class);
    }


    @Override
    public Request getRequest() {
        return new RequestImpl(m);
    }

    @Override
    public SecurityContext getSecurityContext() {
        SecurityContext sc = m.get(SecurityContext.class);
        return sc == null ? new SecurityContextImpl(m) : sc;
    }

    @Override
    public UriInfo getUriInfo() {
        return new UriInfoImpl(m);
    }

    @Override
    public boolean hasEntity() {
        InputStream is = getEntityStream();
        if (is == null) {
            return false;
        }
        // Is Content-Length is explicitly set to 0 ?
        if (HttpUtils.isPayloadEmpty(getHeaders())) {
            return false;
        }

        if (is instanceof DelegatingInputStream) {
            ((DelegatingInputStream) is).cacheInput();
        }

        try {
            return !IOUtils.isEmpty(is);
        } catch (IOException ex) {
            throw ExceptionUtils.toInternalServerErrorException(ex, null);
        }
    }

    @Override
    public void setEntityStream(InputStream is) {
        checkContext();
        m.setContent(InputStream.class, is);
    }

    @Override
    public MultivaluedMap<String, String> getHeaders() {
        h = null;
        return HttpUtils.getModifiableStringHeaders(m);
    }


    @Override
    public void setRequestUri(URI requestUri) throws IllegalStateException {
        if (requestUri.isAbsolute()) {
            String baseUriString = new UriInfoImpl(m).getBaseUri().toString();
            String requestUriString = requestUri.toString();
            if (!requestUriString.startsWith(baseUriString)) {
                String path = requestUri.getRawPath();
                if (StringUtils.isEmpty(path)) {
                    path = "/";
                }
                String query = requestUri.getRawQuery();
                if (!StringUtils.isEmpty(query)) {
                    path = path + "?" + query;
                }
                setRequestUri(requestUri.resolve("/"), URI.create(path));
                return;
            }
            requestUriString = requestUriString.substring(baseUriString.length());
            if (requestUriString.isEmpty()) {
                requestUriString = "/";
            }
            requestUri = URI.create(requestUriString);

        }
        doSetRequestUri(requestUri);
    }

    protected void doSetRequestUri(URI requestUri) throws IllegalStateException {
        checkNotPreMatch();
        // TODO: The JAX-RS TCK requires the full uri toString() rather than just the raw path, but
        // changing to toString() seems to have adverse effects downstream. Needs more investigation.
        HttpUtils.resetRequestURI(m, requestUri.getRawPath());
        String query = requestUri.getRawQuery();
        if (query != null) {
            m.put(Message.QUERY_STRING, query);
        }
    }

    @Override
    public void setRequestUri(URI baseUri, URI requestUri) throws IllegalStateException {
        doSetRequestUri(requestUri);
        Object servletRequest = m.get("HTTP.REQUEST");
        if (servletRequest != null) {
            ((javax.servlet.http.HttpServletRequest)servletRequest)
                .setAttribute(ENDPOINT_ADDRESS_PROPERTY, baseUri.toString());
            
            // The base URI and request URI should be treated differently
            if (requestUri.isAbsolute() && baseUri.resolve("/").compareTo(requestUri.resolve("/")) != 0) {
                ((javax.servlet.http.HttpServletRequest)servletRequest)
                    .setAttribute(ENDPOINT_URI_PROPERTY, requestUri.resolve("/"));
            }
        }
        
    }

    @Override
    public void setSecurityContext(SecurityContext sc) {
        checkContext();
        m.put(SecurityContext.class, sc);
        if (sc instanceof org.apache.cxf.security.SecurityContext) {
            m.put(org.apache.cxf.security.SecurityContext.class,
                  (org.apache.cxf.security.SecurityContext)sc);
        }
    }

    private void checkNotPreMatch() {
        if (!preMatch) {
            throw new IllegalStateException();
        }
    }

    @Override
    public void setMethod(String method) throws IllegalStateException {
        checkNotPreMatch();
        super.setMethod(method);
    }
}
