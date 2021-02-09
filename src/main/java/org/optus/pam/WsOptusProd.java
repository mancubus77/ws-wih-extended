package org.optus.pam;

import org.apache.cxf.endpoint.Client;
import org.apache.cxf.endpoint.ClientCallback;
import org.apache.cxf.endpoint.dynamic.DynamicClientFactory;
import org.apache.cxf.message.Message;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.drools.core.process.instance.impl.WorkItemImpl;
import org.jbpm.bpmn2.core.Bpmn2Import;
import org.jbpm.process.workitem.webservice.WebServiceWorkItemHandler;
import org.apache.cxf.endpoint.Endpoint;
import org.apache.cxf.jaxws.interceptors.HolderInInterceptor;
import org.apache.cxf.jaxws.interceptors.WrapperClassInInterceptor;
import org.jbpm.process.workitem.core.util.Wid;
import org.jbpm.process.workitem.core.util.WidMavenDepends;
import org.jbpm.process.workitem.core.util.WidParameter;
import org.jbpm.process.workitem.core.util.WidResult;
import org.jbpm.process.workitem.core.util.service.WidAction;
import org.jbpm.process.workitem.core.util.service.WidAuth;
import org.jbpm.process.workitem.core.util.service.WidService;
import org.jbpm.workflow.core.impl.WorkflowProcessImpl;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.manager.RuntimeEngine;
import org.kie.api.runtime.manager.RuntimeManager;
import org.kie.api.runtime.process.WorkItem;
import org.kie.api.runtime.process.WorkItemManager;
import org.kie.internal.runtime.manager.RuntimeManagerRegistry;
import org.kie.internal.runtime.manager.context.ProcessInstanceIdContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.namespace.QName;
import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;


/**
 * Web Service Work Item Handler that performs a WebService call.
 * It is not supported out of the box on JDK11+ when running inside WildFly/EAP container
 * with JDK11+ due to Apache CXF not properly resolving classpath.
 *
 * @see https://issues.apache.org/jira/browse/CXF-7925
 */
@Wid(widfile = "WebServiceDefinitions.wid", name = "WebService",
        displayName = "WebService",
        defaultHandler = "mvel: new org.jbpm.process.workitem.webservice.WebServiceWorkItemHandler()",
        documentation = "jbpm-workitems-webservice/index.html",
        category = "jbpm-workitems-webservice",
        icon = "WebService.png",
        parameters = {
                @WidParameter(name = "Url"),
                @WidParameter(name = "Namespace"),
                @WidParameter(name = "Interface"),
                @WidParameter(name = "Operation"),
                @WidParameter(name = "Endpoint"),
                @WidParameter(name = "Parameter"),
                @WidParameter(name = "Mode"),
                @WidParameter(name = "Wrapped")
        },
        results = {
                @WidResult(name = "Result", runtimeType = "java.lang.Object")
        },
        mavenDepends = {
                @WidMavenDepends(group = "org.jbpm", artifact = "jbpm-workitems-webservice", version = "7.44.0.Final")
        },
        serviceInfo = @WidService(category = "WebService", description = "Perform WebService operations",
                keywords = "webservice,call",
                action = @WidAction(title = "Perform a WebService call"),
                authinfo = @WidAuth
        ))


public class WsOptusProd extends WebServiceWorkItemHandler {

    public static final String WSDL_IMPORT_TYPE = "http://schemas.xmlsoap.org/wsdl/";

    private static Logger logger = LoggerFactory.getLogger(WebServiceWorkItemHandler.class);

    private final Long defaultJbpmCxfClientConnectionTimeout = Long.parseLong(System.getProperty("org.jbpm.cxf.client.connectionTimeout", "30000"));
    private final Long defaultJbpmCxfClientReceiveTimeout = Long.parseLong(System.getProperty("org.jbpm.cxf.client.receiveTimeout", "60000"));

    private ConcurrentHashMap<String, Client> clients = new ConcurrentHashMap<String, Client>();
    private DynamicClientFactory dcf = null;
    private KieSession ksession;
    private int asyncTimeout = 10;
    private ClassLoader classLoader;
    private String username;
    private String password;

    enum WSMode {
        SYNC,
        ASYNC,
        ONEWAY;
    }


    public void executeWorkItem(WorkItem workItem,
                                final WorkItemManager manager) {

        // since JaxWsDynamicClientFactory will change the TCCL we need to restore it after creating client
        ClassLoader origClassloader = Thread.currentThread().getContextClassLoader();

        Object[] parameters = null;
        String interfaceRef = (String) workItem.getParameter("Interface");
        String operationRef = (String) workItem.getParameter("Operation");
        String endpointAddress = (String) workItem.getParameter("Endpoint");
        if (workItem.getParameter("Parameter") instanceof Object[]) {
            parameters = (Object[]) workItem.getParameter("Parameter");
        } else if (workItem.getParameter("Parameter") != null && workItem.getParameter("Parameter").getClass().isArray()) {
            int length = Array.getLength(workItem.getParameter("Parameter"));
            parameters = new Object[length];
            for (int i = 0; i < length; i++) {
                parameters[i] = Array.get(workItem.getParameter("Parameter"),
                        i);
            }
        } else {
            parameters = new Object[]{workItem.getParameter("Parameter")};
        }

        String modeParam = (String) workItem.getParameter("Mode");
        WSMode mode = WSMode.valueOf(modeParam == null ? "SYNC" : modeParam.toUpperCase());
        Boolean wrapped = Boolean.parseBoolean((String) workItem.getParameter("Wrapped"));

        try {
            Client client = getWSClient(workItem,
                    interfaceRef);
            if (client == null) {
                throw new IllegalStateException("Unable to create client for web service " + interfaceRef + " - " + operationRef);
            }

            //Override endpoint address if configured.
            if (endpointAddress != null && !"".equals(endpointAddress)) {
                client.getRequestContext().put(Message.ENDPOINT_ADDRESS,
                        endpointAddress);
            }

            // apply authorization if needed
            applyAuthorization(username, password, client);

            //Remove interceptors if using wrapped mode
            if (wrapped) {
                removeWrappingInterceptors(client);
            }

            switch (mode) {
                case SYNC:
                    Object[] result = wrapped ? client.invokeWrapped(operationRef, parameters) : client.invoke(operationRef, parameters);

                    Map<String, Object> output = new HashMap<String, Object>();

                    if (result == null || result.length == 0) {
                        output.put("Result",
                                null);
                    } else {
                        output.put("Result",
                                result[0]);
                    }
                    logger.debug("Received sync response {} completeing work item {}",
                            result,
                            workItem.getId());
                    manager.completeWorkItem(workItem.getId(),
                            output);
                    break;
                case ASYNC:
                    final ClientCallback callback = new ClientCallback();
                    final long workItemId = workItem.getId();
                    final String deploymentId = nonNull(((WorkItemImpl) workItem).getDeploymentId());
                    final long processInstanceId = workItem.getProcessInstanceId();

                    if (wrapped) {
                        client.invokeWrapped(callback, operationRef, parameters);
                    } else {
                        client.invoke(callback, operationRef, parameters);
                    }
                    new Thread(new Runnable() {

                        public void run() {

                            try {

                                Object[] result = callback.get(asyncTimeout,
                                        TimeUnit.SECONDS);
                                Map<String, Object> output = new HashMap<String, Object>();
                                if (callback.isDone()) {
                                    if (result == null) {
                                        output.put("Result",
                                                null);
                                    } else {
                                        output.put("Result",
                                                result[0]);
                                    }
                                }
                                logger.debug("Received async response {} completeing work item {}",
                                        result,
                                        workItemId);

                                RuntimeManager manager = RuntimeManagerRegistry.get().getManager(deploymentId);
                                if (manager != null) {
                                    RuntimeEngine engine = manager.getRuntimeEngine(ProcessInstanceIdContext.get(processInstanceId));

                                    engine.getKieSession().getWorkItemManager().completeWorkItem(workItemId,
                                            output);

                                    manager.disposeRuntimeEngine(engine);
                                } else {
                                    // in case there is no RuntimeManager available use available ksession,
                                    // as it might be used without runtime manager at all
                                    ksession.getWorkItemManager().completeWorkItem(workItemId,
                                            output);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                                throw new RuntimeException("Error encountered while invoking ws operation asynchronously",
                                        e);
                            }
                        }
                    }).start();
                    break;
                case ONEWAY:
                    ClientCallback callbackFF = new ClientCallback();

                    if (wrapped) {
                        client.invokeWrapped(callbackFF, operationRef, parameters);
                    } else {
                        client.invoke(callbackFF, operationRef, parameters);
                    }
                    logger.debug("One way operation, not going to wait for response, completing work item {}",
                            workItem.getId());
                    manager.completeWorkItem(workItem.getId(),
                            new HashMap<String, Object>());
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            handleException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(origClassloader);
        }
    }

    private void removeWrappingInterceptors(Client client) {
        Endpoint endpoint = client.getEndpoint();
        endpoint.getInInterceptors().stream().filter(i -> i instanceof WrapperClassInInterceptor).findFirst().ifPresent(i -> {
            endpoint.getInInterceptors().remove(i);
        });
        endpoint.getInInterceptors().stream().filter(i -> i instanceof HolderInInterceptor).findFirst().ifPresent(i -> {
            endpoint.getInInterceptors().remove(i);
        });
    }

    private ClassLoader getInternalClassLoader() {
        if (this.classLoader != null) {
            return this.classLoader;
        }

        return Thread.currentThread().getContextClassLoader();
    }

    @SuppressWarnings("unchecked")
    protected Client getWSClient(WorkItem workItem,
                                 String interfaceRef) {
        if (clients.containsKey(interfaceRef)) {
            return clients.get(interfaceRef);
        }

        synchronized (this) {

            if (clients.containsKey(interfaceRef)) {
                return clients.get(interfaceRef);
            }

            String importLocation = (String) workItem.getParameter("Url");
            String importNamespace = (String) workItem.getParameter("Namespace");
            if (importLocation != null && importLocation.trim().length() > 0
                    && importNamespace != null && importNamespace.trim().length() > 0) {
                Client client = getDynamicClientFactory().createClient(importLocation,
                        new QName(importNamespace,
                                interfaceRef),
                        getInternalClassLoader(),
                        null);
                setClientTimeout(workItem, client);
                clients.put(interfaceRef,
                        client);
                return client;
            }

            long processInstanceId = ((WorkItemImpl) workItem).getProcessInstanceId();
            WorkflowProcessImpl process = ((WorkflowProcessImpl) ksession.getProcessInstance(processInstanceId).getProcess());
            List<Bpmn2Import> typedImports = (List<Bpmn2Import>) process.getMetaData("Bpmn2Imports");

            if (typedImports != null) {
                Client client = null;
                for (Bpmn2Import importObj : typedImports) {
                    if (WSDL_IMPORT_TYPE.equalsIgnoreCase(importObj.getType())) {
                        try {
                            client = getDynamicClientFactory().createClient(importObj.getLocation(),
                                    new QName(importObj.getNamespace(),
                                            interfaceRef),
                                    getInternalClassLoader(),
                                    null);
                            setClientTimeout(workItem, client);
                            clients.put(interfaceRef,
                                    client);
                            return client;
                        } catch (Exception e) {
                            logger.error("Error when creating WS Client",
                                    e);
                            continue;
                        }
                    }
                }
            }
        }
        return null;
    }


    private void setClientTimeout(WorkItem workItem, Client client) {
        HTTPConduit conduit = (HTTPConduit) client.getConduit();
        HTTPClientPolicy policy = conduit.getClient();

        long connectionTimeout = defaultJbpmCxfClientConnectionTimeout;
        String connectionTimeoutStr = (String) workItem.getParameter("ConnectionTimeout");
        if (connectionTimeoutStr != null && !connectionTimeoutStr.trim().isEmpty()) {
            connectionTimeout = Long.valueOf(connectionTimeoutStr);
        }
        long receiveTimeout = defaultJbpmCxfClientReceiveTimeout;
        String receiveTimeoutStr = (String) workItem.getParameter("ReceiveTimeout");
        if (receiveTimeoutStr != null && !receiveTimeoutStr.trim().isEmpty()) {
            receiveTimeout = Long.valueOf(receiveTimeoutStr);
        }

        logger.debug("connectionTimeout = {}, receiveTimeout = {}", connectionTimeout, receiveTimeout);
        policy.setConnectionTimeout(connectionTimeout);
        policy.setReceiveTimeout(receiveTimeout);
    }

}

