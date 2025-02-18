/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.web;

import org.apache.nifi.util.NiFiProperties;
import org.apache.nifi.web.security.anonymous.NiFiAnonymousAuthenticationFilter;
import org.apache.nifi.web.security.anonymous.NiFiAnonymousAuthenticationProvider;
import org.apache.nifi.web.security.jwt.JwtAuthenticationFilter;
import org.apache.nifi.web.security.jwt.JwtAuthenticationProvider;
import org.apache.nifi.web.security.jwt.NiFiBearerTokenResolver;
import org.apache.nifi.web.security.knox.KnoxAuthenticationFilter;
import org.apache.nifi.web.security.knox.KnoxAuthenticationProvider;
import org.apache.nifi.web.security.oidc.OIDCEndpoints;
import org.apache.nifi.web.security.saml.SAMLEndpoints;
import org.apache.nifi.web.security.x509.X509AuthenticationFilter;
import org.apache.nifi.web.security.x509.X509AuthenticationProvider;
import org.apache.nifi.web.security.x509.X509CertificateExtractor;
import org.apache.nifi.web.security.x509.X509IdentityProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.preauth.x509.X509PrincipalExtractor;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

/**
 * NiFi Web Api Spring security. Applies the various NiFiAuthenticationFilter servlet filters which will extract authentication
 * credentials from API requests.
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class NiFiWebApiSecurityConfiguration extends WebSecurityConfigurerAdapter {
    private NiFiProperties properties;

    private X509AuthenticationFilter x509AuthenticationFilter;
    private X509CertificateExtractor certificateExtractor;
    private X509PrincipalExtractor principalExtractor;
    private X509IdentityProvider certificateIdentityProvider;
    private X509AuthenticationProvider x509AuthenticationProvider;

    private JwtAuthenticationFilter jwtAuthenticationFilter;
    private JwtAuthenticationProvider jwtAuthenticationProvider;

    private KnoxAuthenticationFilter knoxAuthenticationFilter;
    private KnoxAuthenticationProvider knoxAuthenticationProvider;

    private NiFiAnonymousAuthenticationFilter anonymousAuthenticationFilter;
    private NiFiAnonymousAuthenticationProvider anonymousAuthenticationProvider;

    public NiFiWebApiSecurityConfiguration() {
        super(true); // disable defaults
    }

    /**
     * Configure Web Security with ignoring matchers for authentication requests
     *
     * @param webSecurity Spring Web Security Configuration
     */
    @Override
    public void configure(final WebSecurity webSecurity) {
        webSecurity
                .ignoring()
                    .antMatchers(
                            "/access",
                            "/access/config",
                            "/access/token",
                            "/access/kerberos",
                            OIDCEndpoints.TOKEN_EXCHANGE,
                            OIDCEndpoints.LOGIN_REQUEST,
                            OIDCEndpoints.LOGIN_CALLBACK,
                            OIDCEndpoints.LOGOUT_CALLBACK,
                            "/access/knox/callback",
                            "/access/knox/request",
                            SAMLEndpoints.SERVICE_PROVIDER_METADATA,
                            SAMLEndpoints.LOGIN_REQUEST,
                            SAMLEndpoints.LOGIN_CONSUMER,
                            SAMLEndpoints.LOGIN_EXCHANGE,
                            // the logout sequence will be protected by a request identifier set in a Cookie so these
                            // paths need to be listed here in order to pass through our normal authn filters
                            SAMLEndpoints.SINGLE_LOGOUT_REQUEST,
                            SAMLEndpoints.SINGLE_LOGOUT_CONSUMER,
                            SAMLEndpoints.LOCAL_LOGOUT,
                            "/access/logout/complete");
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        NiFiCsrfTokenRepository csrfRepository = new NiFiCsrfTokenRepository();
        csrfRepository.setHeaderName(NiFiBearerTokenResolver.AUTHORIZATION);
        csrfRepository.setCookieName(NiFiBearerTokenResolver.JWT_COOKIE_NAME);

        http
                .cors().and()
                .rememberMe().disable()
                .authorizeRequests().anyRequest().fullyAuthenticated().and()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                .csrf().requireCsrfProtectionMatcher(new AndRequestMatcher(CsrfFilter.DEFAULT_CSRF_MATCHER, new CsrfCookieRequestMatcher())).csrfTokenRepository(csrfRepository);

        // x509
        http.addFilterBefore(x509FilterBean(), AnonymousAuthenticationFilter.class);

        // jwt
        http.addFilterBefore(jwtFilterBean(), AnonymousAuthenticationFilter.class);

        // knox
        http.addFilterBefore(knoxFilterBean(), AnonymousAuthenticationFilter.class);

        // anonymous
        http.addFilterAfter(anonymousFilterBean(), AnonymousAuthenticationFilter.class);

        // disable default anonymous handling because it doesn't handle conditional authentication well
        http.anonymous().disable();
    }


    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedMethods(Arrays.asList("HEAD", "GET"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/process-groups/*/templates/upload", configuration);
        return source;
    }

    @Bean
    @Override
    public AuthenticationManager authenticationManagerBean() throws Exception {
        // override xxxBean method so the authentication manager is available in app context (necessary for the method level security)
        return super.authenticationManagerBean();
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth
                .authenticationProvider(x509AuthenticationProvider)
                .authenticationProvider(jwtAuthenticationProvider)
                .authenticationProvider(knoxAuthenticationProvider)
                .authenticationProvider(anonymousAuthenticationProvider);
    }

    @Bean
    public JwtAuthenticationFilter jwtFilterBean() throws Exception {
        if (jwtAuthenticationFilter == null) {
            jwtAuthenticationFilter = new JwtAuthenticationFilter();
            jwtAuthenticationFilter.setProperties(properties);
            jwtAuthenticationFilter.setAuthenticationManager(authenticationManager());
        }
        return jwtAuthenticationFilter;
    }

    @Bean
    public KnoxAuthenticationFilter knoxFilterBean() throws Exception {
        if (knoxAuthenticationFilter == null) {
            knoxAuthenticationFilter = new KnoxAuthenticationFilter();
            knoxAuthenticationFilter.setProperties(properties);
            knoxAuthenticationFilter.setAuthenticationManager(authenticationManager());
        }
        return knoxAuthenticationFilter;
    }

    @Bean
    public X509AuthenticationFilter x509FilterBean() throws Exception {
        if (x509AuthenticationFilter == null) {
            x509AuthenticationFilter = new X509AuthenticationFilter();
            x509AuthenticationFilter.setProperties(properties);
            x509AuthenticationFilter.setCertificateExtractor(certificateExtractor);
            x509AuthenticationFilter.setPrincipalExtractor(principalExtractor);
            x509AuthenticationFilter.setAuthenticationManager(authenticationManager());
        }
        return x509AuthenticationFilter;
    }

    @Bean
    public NiFiAnonymousAuthenticationFilter anonymousFilterBean() throws Exception {
        if (anonymousAuthenticationFilter == null) {
            anonymousAuthenticationFilter = new NiFiAnonymousAuthenticationFilter();
            anonymousAuthenticationFilter.setProperties(properties);
            anonymousAuthenticationFilter.setAuthenticationManager(authenticationManager());
        }
        return anonymousAuthenticationFilter;
    }

    @Autowired
    public void setProperties(NiFiProperties properties) {
        this.properties = properties;
    }

    @Autowired
    public void setJwtAuthenticationProvider(JwtAuthenticationProvider jwtAuthenticationProvider) {
        this.jwtAuthenticationProvider = jwtAuthenticationProvider;
    }

    @Autowired
    public void setKnoxAuthenticationProvider(KnoxAuthenticationProvider knoxAuthenticationProvider) {
        this.knoxAuthenticationProvider = knoxAuthenticationProvider;
    }

    @Autowired
    public void setAnonymousAuthenticationProvider(NiFiAnonymousAuthenticationProvider anonymousAuthenticationProvider) {
        this.anonymousAuthenticationProvider = anonymousAuthenticationProvider;
    }

    @Autowired
    public void setX509AuthenticationProvider(X509AuthenticationProvider x509AuthenticationProvider) {
        this.x509AuthenticationProvider = x509AuthenticationProvider;
    }

    @Autowired
    public void setCertificateExtractor(X509CertificateExtractor certificateExtractor) {
        this.certificateExtractor = certificateExtractor;
    }

    @Autowired
    public void setPrincipalExtractor(X509PrincipalExtractor principalExtractor) {
        this.principalExtractor = principalExtractor;
    }

    @Autowired
    public void setCertificateIdentityProvider(X509IdentityProvider certificateIdentityProvider) {
        this.certificateIdentityProvider = certificateIdentityProvider;
    }
}
