/*
 * Copyright 2018 Chris Bragg
 * Adapted from the Slack Plugin by Andrew Karpow based on the HipChat Plugin by Hayden Bakkum
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.sift.rundeck.plugins.googlechat;

import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.core.plugins.configuration.PropertyScope;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty;
import com.dtolabs.rundeck.plugins.notification.NotificationPlugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.util.*;

import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.FileTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

/**
 * Sends Rundeck job notification messages to a google chat thread.
 *
 * @author Hayden Bakkum
 */
@Plugin(service= "Notification", name="GoogleChatNotification")
@PluginDescription(title="GoogleChat", description="Sends Rundeck Notifications to GoogleChat")
public class GoogleChatNotificationPlugin implements NotificationPlugin {

    private static final String GOOGLECHAT_HOOK = ".google.com";
    private static final String GOOGLECHAT_API_WEBHOOK_PATH = "https://chat.googleapis.com/v1/spaces/AAAAeWa6Qw4/messages?key=XXXXXXXXXXX";

    private static final String GOOGLECHAT_MESSAGE_COLOR_GREEN = "good";
    private static final String GOOGLECHAT_MESSAGE_COLOR_YELLOW = "warning";
    private static final String GOOGLECHAT_MESSAGE_COLOR_RED = "danger";

    private static final String GOOGLECHAT_MESSAGE_FROM_NAME = "Rundeck";
    private static final String GOOGLECHAT_EXT_MESSAGE_TEMPLATE_PATH = "/etc/rundeck/templates";
    private static final String GOOGLECHAT_MESSAGE_TEMPLATE = "google-chat-message.ftl";

    private static final String TRIGGER_START = "start";
    private static final String TRIGGER_SUCCESS = "success";
    private static final String TRIGGER_FAILURE = "failure";

    private static final Map<String, GoogleChatNotificationData> TRIGGER_NOTIFICATION_DATA = new HashMap<String, GoogleChatNotificationData>();

    private static final Configuration FREEMARKER_CFG = new Configuration();



    @PluginProperty(
            title = "Google Chat Hangouts WEBHOOK",
            description = "Google Chat Hangouts Webhook for sending notifications.",
            defaultValue = "",
            required = false
    )
    private String googleChatWebhook;

    @PluginProperty(
        title = "Proxy Host",
        description = "Proxy host to use when communicating to GoogleChat.",
        required = false,
        defaultValue = "",
        scope = PropertyScope.Project)
    private String proxyHost;

    @PluginProperty(
            title = "Proxy Port",
            description = "Proxy port to use when communicating to GoogleChat.",
            required = false,
            defaultValue = "",
            scope = PropertyScope.Project)
    private String proxyPort;

    @PluginProperty(
            title = "External Template",
            description = "External Freemarker Template to use for notifications",
            required = false,
            defaultValue = ""
    )
    private String external_template;


    /**
     * Sends a message to a Google Chat thread when a job notification event is raised by Rundeck.
     *
     * @param trigger name of job notification event causing notification
     * @param executionData job execution data passed from Rundeck
     * @param config plugin configuration
     * @throws GoogleChatNotificationPluginException when any error occurs sending the Google Chat message
     * @return true, if the Google API response indicates a message was successfully delivered to a chat room
     */
    public boolean postNotification(String trigger, Map executionData, Map config) {

        String ACTUAL_GOOGLECHAT_TEMPLATE;

        if(null != external_template && !external_template.isEmpty()) {
            try {
                FileTemplateLoader externalTemplate = new FileTemplateLoader(new File(GOOGLECHAT_EXT_MESSAGE_TEMPLATE_PATH));
                System.err.printf("Found external template directory. Using it.\n");
                TemplateLoader[] loaders = new TemplateLoader[]{externalTemplate};
                MultiTemplateLoader mtl = new MultiTemplateLoader(loaders);
                FREEMARKER_CFG.setTemplateLoader(mtl);
                ACTUAL_GOOGLECHAT_TEMPLATE = external_template;
            } catch (Exception e) {
                System.err.printf("No such directory: %s\n", GOOGLECHAT_EXT_MESSAGE_TEMPLATE_PATH);
                return false;
            }
        }else{
            ClassTemplateLoader builtInTemplate = new ClassTemplateLoader(GoogleChatNotificationPlugin.class, "/templates");
            TemplateLoader[] loaders = new TemplateLoader[]{builtInTemplate};
            MultiTemplateLoader mtl = new MultiTemplateLoader(loaders);
            FREEMARKER_CFG.setTemplateLoader(mtl);
            ACTUAL_GOOGLECHAT_TEMPLATE = GOOGLECHAT_MESSAGE_TEMPLATE;
        }

        TRIGGER_NOTIFICATION_DATA.put(TRIGGER_START,   new GoogleChatNotificationData(ACTUAL_GOOGLECHAT_TEMPLATE, GOOGLECHAT_MESSAGE_COLOR_YELLOW));
        TRIGGER_NOTIFICATION_DATA.put(TRIGGER_SUCCESS, new GoogleChatNotificationData(ACTUAL_GOOGLECHAT_TEMPLATE, GOOGLECHAT_MESSAGE_COLOR_GREEN));
        TRIGGER_NOTIFICATION_DATA.put(TRIGGER_FAILURE, new GoogleChatNotificationData(ACTUAL_GOOGLECHAT_TEMPLATE, GOOGLECHAT_MESSAGE_COLOR_RED));

        String ACTUAL_GOOGLECHAT_API_WEBHOOK_PATH;
        if(null != googleChatWebhook && !googleChatWebhook.isEmpty()) {
            ACTUAL_GOOGLECHAT_API_WEBHOOK_PATH = googleChatWebhook;
        }else{
            ACTUAL_GOOGLECHAT_API_WEBHOOK_PATH = GOOGLECHAT_API_WEBHOOK_PATH;
        }

        try {
            FREEMARKER_CFG.setSetting(Configuration.CACHE_STORAGE_KEY, "strong:20, soft:250");
        }catch(Exception e){
            System.err.printf("Got and exception from Freemarker: %s", e.getMessage());
        }

        if (!TRIGGER_NOTIFICATION_DATA.containsKey(trigger)) {
            throw new IllegalArgumentException("Unknown trigger type: [" + trigger + "].");
        }

        String message = generateMessage(trigger, executionData, config);
        String googleResponse = invokeGoogleAPIMethod(message);

        if ("error".equals(googleResponse)) {
            return false;
        } else {
            // Unfortunately there seems to be no way to obtain a reference to the plugin logger within notification plugins,
            // but throwing an exception will result in its message being logged.
            throw new GoogleChatNotificationPluginException("Unknown status returned from Google API: [" + googleResponse + "].");
        }
    }

    private String generateMessage(String trigger, Map executionData, Map config) {
        String templateName = TRIGGER_NOTIFICATION_DATA.get(trigger).template;
        String color = TRIGGER_NOTIFICATION_DATA.get(trigger).color;

        HashMap<String, Object> model = new HashMap<String, Object>();
        model.put("trigger", trigger);
        model.put("color", color);
        model.put("executionData", executionData);
        model.put("config", config);
        StringWriter sw = new StringWriter();
        try {
            Template template = FREEMARKER_CFG.getTemplate(templateName);
            template.process(model,sw);
        } catch (IOException ioEx) {
            throw new GoogleChatNotificationPluginException("Error loading Google notification message template: [" + ioEx.getMessage() + "].", ioEx);
        } catch (TemplateException templateEx) {
            throw new GoogleChatNotificationPluginException("Error merging Google notification message template: [" + templateEx.getMessage() + "].", templateEx);
        }

        return sw.toString();
    }

    private String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException unsupportedEncodingException) {
            throw new GoogleChatNotificationPluginException("URL encoding error: [" + unsupportedEncodingException.getMessage() + "].", unsupportedEncodingException);
        }
    }

    private String invokeGoogleAPIMethod(String message) {
        
        URL requestUrl = toURL(ACTUAL_GOOGLECHAT_API_WEBHOOK_PATH);

        HttpURLConnection connection = null;
        InputStream responseStream = null;
        try {
            connection = openConnection(requestUrl);
            putRequestStream(connection, message);
            responseStream = getResponseStream(connection);
            return getGoogleResponse(responseStream);

        } finally {
            closeQuietly(responseStream);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private URL toURL(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException malformedURLEx) {
            throw new GoogleChatNotificationPluginException("Google API URL is malformed: [" + malformedURLEx.getMessage() + "].", malformedURLEx);
        }
    }

    private HttpURLConnection openConnection(URL requestUrl) {
        try {
               
               if (isProxySet()) {
                    Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, Integer.parseInt(proxyPort)));
                    return (HttpURLConnection) requestUrl.openConnection(proxy);
               } else {
                    return (HttpURLConnection) requestUrl.openConnection();
               }
            
        } catch (IOException ioEx) {
            throw new GoogleChatNotificationPluginException("Error opening connection to Google URL: [" + ioEx.getMessage() + "].", ioEx);
        }
    }

    private void putRequestStream(HttpURLConnection connection, String message) {
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("charset", "utf-8");

            connection.setDoInput(true);
            connection.setDoOutput(true);
            DataOutputStream wr = new DataOutputStream(connection.getOutputStream());
            wr.writeBytes(message);
            wr.flush();
            wr.close();
        } catch (IOException ioEx) {
            throw new GoogleChatNotificationPluginException("Error putting data to Google URL: [" + ioEx.getMessage() + "].", ioEx);
        }
    }

    private InputStream getResponseStream(HttpURLConnection connection) {
        InputStream input = null;
        try {
            input = connection.getInputStream();
        } catch (IOException ioEx) {
            input = connection.getErrorStream();
        }
        return input;
    }


    private int getResponseCode(HttpURLConnection connection) {
        try {
            return connection.getResponseCode();
        } catch (IOException ioEx) {
            throw new GoogleChatNotificationPluginException("Failed to obtain HTTP response: [" + ioEx.getMessage() + "].", ioEx);
        }
    }

    private String getGoogleResponse(InputStream responseStream) {
        try {
            return new Scanner(responseStream,"UTF-8").useDelimiter("\\A").next();
        } catch (Exception ioEx) {
            throw new GoogleChatNotificationPluginException("Error reading Google API JSON response: [" + ioEx.getMessage() + "].", ioEx);
        }
    }

    private void closeQuietly(InputStream input) {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ioEx) {
                // ignore
            }
        }
    }

    private static class GoogleChatNotificationData {
        private String template;
        private String color;
        public GoogleChatNotificationData(String template, String color) {
            this.color = color;
            this.template = template;
        }
    }

    private boolean isProxySet() {
        return isNotEmpty(proxyHost);
    }

    public static boolean isNotEmpty(final String value) {
        return value != null && !"".equals(value);
    }

}
