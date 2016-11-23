package dk.langli.jensen.broker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import dk.langli.jensen.JsonBrokerBuilder;
import dk.langli.jensen.JsonRpcResponse;
import dk.langli.jensen.Request;

public class JsonRpcBroker {
	public static final String JSONRPC = "2.0";
	private final Logger log = LoggerFactory.getLogger(JsonRpcBroker.class);
	private final ObjectMapper mapper;
	private final ReturnValueHandler returnValueHandler;
	private final ResponseHandler responseHandler;
	private final InvocationIntercepter invocationIntercepter;
	private final MethodLocator methodLocator;
	private final InstanceLocator instanceLocator;
	private final PrettyPrinter prettyPrinter;
	private final SecurityFilter securityFilter;

	public JsonRpcBroker(JsonBrokerBuilder builder) {
		mapper = builder.getObjectMapper() != null ? builder.getObjectMapper() : new ObjectMapper();
		returnValueHandler = builder.getReturnValueHandler();
		responseHandler = builder.getResponseHandler();
		invocationIntercepter = builder.getInvocationIntercepter();
		instanceLocator = builder.getInstanceLocator() != null ? builder.getInstanceLocator() : new DefaultInstanceLocator();
		prettyPrinter = builder.getPrettyPrinter();
		securityFilter = builder.getSecurityFilter();
		methodLocator = builder.getMethodLocator() != null ? builder.getMethodLocator() : new DefaultMethodLocator(mapper);
	}

	public String invoke(String jsonRequest) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		invoke(jsonRequest, out);
		return out.size() == 0 ? null : out.toString();
	}

	public void invoke(InputStream jsonRequest, OutputStream out) {
		String request = null;
		try {
			request = IOUtils.toString(jsonRequest);
		}
		catch(IOException e) {
			log.error("Cannot read jsonRequest from InputStream", e);
		}
		invoke(request, out);
	}

	public void invoke(String jsonRequest, OutputStream out) {
		JsonRpcResponse response = null;
		Request request = null;
		Object id = null;
		try {
			request = mapper.readValue(jsonRequest, Request.class);
			id = request != null ? request.getId() : null;
			if(request != null) {
				if(request.getJsonrpc().equals(JSONRPC)) {
					if(securityFilter != null && !securityFilter.isAllowed(request)) {
						String message = String.format("Invocation of %s not allowed", request.getMethod());
						throw new JsonRpcException(JsonRpcError.INVALID_REQUEST.toError(new SecurityException(message), request));
					}
					else {
						MethodCall methodCall = methodLocator.getInvocation(request);
						Object result = invoke(methodCall, request);
						if(returnValueHandler != null) {
							result = returnValueHandler.onReturnValue(result);
						}
						response = new JsonRpcResponse(id, result, null);
					}
				}
				else {
					throw new JsonRpcException(JsonRpcError.INVALID_REQUEST.toError(new Exception("JSON-RPC " + request.getJsonrpc() + " is unsupported.  Use " + JSONRPC + " instead"), request));
				}
			}
			else {
				throw new JsonRpcException(JsonRpcError.INVALID_REQUEST.toError(new Exception("Request is missing"), request));
			}
		}
		catch(JsonRpcException e) {
			response = new JsonRpcResponse(id, null, e.getError());
		}
		catch(MethodNotFoundException e) {
			response = new JsonRpcResponse(id, null, JsonRpcError.METHOD_NOT_FOUND.toError(e, e.getIncompatibleMethods(), request));
		}
		catch(ClassNotFoundException e) {
			response = new JsonRpcResponse(id, null, JsonRpcError.METHOD_NOT_FOUND.toError(e, request));
		}
		catch(JsonMappingException | JsonParseException e) {
			response = new JsonRpcResponse(id, null, JsonRpcError.PARSE_ERROR.toError(e, request));
		}
		catch(IOException e) {
			response = new JsonRpcResponse(id, null, JsonRpcError.SERVER_ERROR.toError(e, request));
		}
        catch(Exception e) {
            response = new JsonRpcResponse(id, null, JsonRpcError.INTERNAL_ERROR.toError(e, request));
        }
		if(id != null || request == null) {
			try {
				if(responseHandler != null) {
					response = responseHandler.onResponse(response);
				}
				write(response, out);
			}
			catch(JsonGenerationException e) {
				tryWrite(new JsonRpcResponse(id, null, JsonRpcError.INTERNAL_ERROR.toError(e, request)), out);
			}
			catch(JsonMappingException e) {
				tryWrite(new JsonRpcResponse(id, null, JsonRpcError.INTERNAL_ERROR.toError(e, request)), out);
			}
			catch(IOException e) {
				tryWrite(new JsonRpcResponse(id, null, JsonRpcError.INTERNAL_ERROR.toError(e, request)), out);
			}
			catch(JsonRpcException e) {
				tryWrite(new JsonRpcResponse(id, null, JsonRpcError.SERVER_ERROR.toError(e, request)), out);
			}
		}
	}

	private void tryWrite(JsonRpcResponse response, OutputStream out) {
		try {
			write(response, out);
		}
		catch(JsonGenerationException e) {
			log.error("Cannot write response to OutputStream", e);
		}
		catch(JsonMappingException e) {
			log.error("Cannot write response to OutputStream", e);
		}
		catch(IOException e) {
			log.error("Cannot write response to OutputStream", e);
		}
	}

	private void write(JsonRpcResponse response, OutputStream out) throws JsonGenerationException, JsonMappingException, IOException {
		ObjectWriter writer = mapper.writer();
		if(prettyPrinter != null) {
			writer = writer.with(prettyPrinter);
		}
		writer.writeValue(out, response);
	}

	private Object invoke(MethodCall methodCall, Request request) throws JsonRpcException {
		Object result = null;
		Method method = methodCall.getMethod();
		String methodSignature = getMethodSignature(methodCall);
		try {
			log.trace("Call " + methodSignature);
			Object instance = null;
			if(!Modifier.isStatic(method.getModifiers())) {
				instance = getInstance(method.getDeclaringClass(), request);
			}
			if(invocationIntercepter != null) {
				invocationIntercepter.onBeforeInvocation(method, instance, methodCall.getParams());
			}
			result = method.invoke(instance, methodCall.getParams().toArray());
		}
		catch(IllegalArgumentException e) {
			String message = "Method call " + methodSignature + " cannot accept the parameters given (" + methodCall.getParams().size() + (methodCall.getParams().size() == method.getParameterTypes().length ? " = " : " != ") + method.getParameterTypes().length + ")";
			log.warn(message, e);
			throw new JsonRpcException(JsonRpcError.INVALID_PARAMS.toError(new Exception(message), request));
		}
		catch(IllegalAccessException e) {
			String message = "Method call " + methodSignature + " cannot be accessed";
			log.warn(message, e);
			throw new JsonRpcException(JsonRpcError.INVALID_PARAMS.toError(new Exception(message), request));
		}
		catch(InvocationTargetException e) {
			String message = "Method call " + methodSignature + " threw an Exception";
			log.warn(message, e);
			throw new JsonRpcException(JsonRpcError.SERVER_ERROR.toError(e.getTargetException(), request));
		}
		return result;
	}

	private String getMethodSignature(MethodCall methodCall) {
		Method method = methodCall.getMethod();
		List<? extends Object> params = methodCall.getParams();
		String methodSignature = method.getName() + "(";
		for(int i = 0; params != null && i < params.size(); i++) {
			methodSignature += (i != 0 ? ", " : "") + params.get(i).getClass().getSimpleName();
		}
		methodSignature += ")";
		return methodSignature;
	}

	private Object getInstance(Class<?> clazz, Request request) throws JsonRpcException {
		Object instance = null;
		try {
			instance = instanceLocator.getInstance(clazz);
		}
		catch(InstantiationException e) {
			String message = "Class " + clazz.getSimpleName() + " cannot be instantiated with a no-args constructor";
			log.warn(message, e);
			throw new JsonRpcException(JsonRpcError.METHOD_NOT_FOUND.toError(new Exception(message), request));
		}
		catch(IllegalAccessException e) {
			String message = "Class " + clazz.getSimpleName() + " must not be instantiated with a no-args constructor";
			log.warn(message, e);
			throw new JsonRpcException(JsonRpcError.METHOD_NOT_FOUND.toError(new Exception(message), request));
		}
		return instance;
	}


	public ReturnValueHandler getReturnValueHandler() {
		return returnValueHandler;
	}

	public ResponseHandler getResponseHandler() {
		return responseHandler;
	}

	public InvocationIntercepter getInvocationIntercepter() {
		return invocationIntercepter;
	}

	public InstanceLocator getInstanceFinder() {
		return instanceLocator;
	}
}
