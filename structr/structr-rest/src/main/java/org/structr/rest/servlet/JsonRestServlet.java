/*
 *  Copyright (C) 2011 Axel Morgner
 *
 *  This file is part of structr <http://structr.org>.
 *
 *  structr is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  structr is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with structr.  If not, see <http://www.gnu.org/licenses/>.
 */



package org.structr.rest.servlet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.FileWriter;

import org.apache.commons.lang.StringUtils;

import org.structr.common.AccessMode;
import org.structr.common.PropertyView;
import org.structr.common.SecurityContext;
import org.structr.core.Value;
import org.structr.core.node.NodeAttribute;
import org.structr.rest.ResourceConstraintProvider;
import org.structr.rest.RestMethodResult;
import org.structr.core.PropertySetGSONAdapter;
import org.structr.rest.adapter.ResultGSONAdapter;
import org.structr.rest.constraint.PagingConstraint;
import org.structr.rest.constraint.RelationshipFollowingConstraint;
import org.structr.rest.constraint.ResourceConstraint;
import org.structr.rest.constraint.Result;
import org.structr.rest.constraint.SortConstraint;
import org.structr.rest.exception.IllegalPathException;
import org.structr.rest.exception.MessageException;
import org.structr.rest.exception.NoResultsException;
import org.structr.core.PropertySet;
import org.structr.core.PropertySet.PropertyFormat;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;
import java.io.Writer;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.structr.common.error.FrameworkException;
import org.structr.core.Services;
import org.structr.rest.adapter.FrameworkExceptionGSONAdapter;

//~--- classes ----------------------------------------------------------------

/**
 * Implements the structr REST API. The input and output format of the JSON
 * serialisation can be configured with the servlet init parameter "PropertyFormat".
 * ({@see PropertyFormat}).
 *
 * @author Christian Morgner
 */
public class JsonRestServlet extends HttpServlet {

	public static final int DEFAULT_VALUE_PAGE_SIZE                     = 20;
	public static final String DEFAULT_VALUE_SORT_ORDER                 = "asc";
	public static final String REQUEST_PARAMETER_PAGE_NUMBER            = "page";
	public static final String REQUEST_PARAMETER_PAGE_SIZE              = "pageSize";
	public static final String REQUEST_PARAMETER_SORT_KEY               = "sort";
	public static final String REQUEST_PARAMETER_SORT_ORDER             = "order";
	public static final String REQUEST_PARAMETER_SEARCH_STRICT          = "strict";
	private static final String SERVLET_PARAMETER_CONSTRAINT_PROVIDER   = "ConstraintProvider";
	private static final String SERVLET_PARAMETER_DEFAULT_PROPERTY_VIEW = "DefaultPropertyView";
	private static final String SERVLET_PARAMETER_ID_PROPERTY           = "IdProperty";
	private static final String SERVLET_PARAMETER_PROPERTY_FORMAT       = "PropertyFormat";
	private static final String SERVLET_PARAMETER_REQUEST_LOGGING       = "RequestLogging";
	private static final Logger logger                                  = Logger.getLogger(JsonRestServlet.class.getName());

	//~--- fields ---------------------------------------------------------

	private SimpleDateFormat accessLogDateFormat                   = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
	private String defaultPropertyView                             = PropertyView.Public;
	private Map<Pattern, Class> constraintMap                      = null;
	private String defaultIdProperty                               = null;
	private Gson gson                                              = null;
	private PropertySetGSONAdapter propertySetAdapter              = null;
	private Value<String> propertyView                             = null;
	private ResultGSONAdapter resultGsonAdapter                    = null;
	private Writer logWriter                                       = null;

	//~--- methods --------------------------------------------------------

	@Override
	public void init() {

		// initialize variables
		this.constraintMap        = new LinkedHashMap<Pattern, Class>();
		this.propertyView         = new ThreadLocalPropertyView();

		// external resource constraint initialization
		String externalProviderName = this.getInitParameter(SERVLET_PARAMETER_CONSTRAINT_PROVIDER);

		if (externalProviderName != null) {

			String[] parts = externalProviderName.split("[, ]+");

			for (String part : parts) {

				try {

					logger.log(Level.INFO, "Injecting constraints from provider {0}", part);

					Class providerClass                 = Class.forName(part);
					ResourceConstraintProvider provider = (ResourceConstraintProvider) providerClass.newInstance();

					// inject constraints
					constraintMap.putAll(provider.getConstraints());

				} catch (Throwable t) {
					logger.log(Level.WARNING, "Unable to inject external resource constraints", t);
				}

			}

		}

		// property view initialization
		String defaultPropertyViewName = this.getInitParameter(SERVLET_PARAMETER_DEFAULT_PROPERTY_VIEW);

		if (defaultPropertyViewName != null) {

			logger.log(Level.INFO, "Setting default property view to {0}", defaultPropertyViewName);

			this.defaultPropertyView = defaultPropertyViewName;

		}

		// primary key
		String defaultIdPropertyName = this.getInitParameter(SERVLET_PARAMETER_ID_PROPERTY);

		if (defaultIdPropertyName != null) {

			logger.log(Level.INFO, "Setting default id property to {0}", defaultIdPropertyName);

			this.defaultIdProperty = defaultIdPropertyName;

		}

		PropertyFormat propertyFormat = initializePropertyFormat();

		// initialize adapters
		this.resultGsonAdapter  = new ResultGSONAdapter(propertyFormat, propertyView, defaultIdProperty);
		this.propertySetAdapter = new PropertySetGSONAdapter(propertyFormat, defaultIdProperty);

		// create GSON serializer
		this.gson = new GsonBuilder()
			.setPrettyPrinting()
			.serializeNulls()
			.registerTypeHierarchyAdapter(FrameworkException.class, new FrameworkExceptionGSONAdapter())
			.registerTypeAdapter(PropertySet.class, propertySetAdapter)
			.registerTypeAdapter(Result.class, resultGsonAdapter)
			.create();

		String requestLoggingParameter = this.getInitParameter(SERVLET_PARAMETER_REQUEST_LOGGING);
		if(requestLoggingParameter != null && "true".equalsIgnoreCase(requestLoggingParameter)) {

			// initialize access log
			String logFileName = Services.getBasePath().concat("/logs/access.log");
			try {
				File logFile = new File(logFileName);
				logFile.getParentFile().mkdir();
				logWriter = new FileWriter(logFileName);

			} catch(IOException ioex) {
				logger.log(Level.WARNING, "Could not open access log file {0}: {1}", new Object[] { logFileName, ioex.getMessage() } );
			}
		}
	}

	@Override
	public void destroy() {
		if(logWriter != null) {
			try {
				logWriter.flush();
				logWriter.close();
			} catch(IOException ioex) {
				logger.log(Level.WARNING, "Could not close access log file.", ioex);
			}
		}
	}

	// <editor-fold defaultstate="collapsed" desc="DELETE">
	@Override
	protected void doDelete(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {

		try {
			logRequest("DELETE", request);

			request.setCharacterEncoding("UTF-8");
			response.setContentType("application/json; charset=utf-8");

			SecurityContext securityContext = getSecurityContext(request);
			if(securityContext != null) {

				// evaluate constraint chain
				List<ResourceConstraint> chain        = parsePath(securityContext, request);
				ResourceConstraint resourceConstraint = optimizeConstraintChain(chain);

				// do action
				RestMethodResult result = resourceConstraint.doDelete();
				result.commitResponse(gson, response);

			} else {

				RestMethodResult result = new RestMethodResult(HttpServletResponse.SC_FORBIDDEN);
				result.commitResponse(gson, response);
			}

		} catch (IllegalArgumentException illegalArgumentException) {
			handleValidationError(illegalArgumentException, response);
		} catch (FrameworkException frameworkException) {

			int code = frameworkException.getStatus();

			response.setStatus(code);
			response.getWriter().append(jsonError(code, frameworkException.getMessage()));

		} catch (JsonSyntaxException jsex) {

			logger.log(Level.WARNING, "JsonSyntaxException in DELETE", jsex);

			int code = HttpServletResponse.SC_BAD_REQUEST;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in DELETE: " + jsex.getMessage()));

		} catch (JsonParseException jpex) {

			logger.log(Level.WARNING, "JsonParseException in DELETE", jpex);

			int code = HttpServletResponse.SC_BAD_REQUEST;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in DELETE: " + jpex.getMessage()));

		} catch (Throwable t) {

			logger.log(Level.WARNING, "Exception in DELETE", t);

			int code = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in DELETE: " + t.getMessage()));
		}
	}

	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="GET">
	@Override
	protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {

		try {
			logRequest("GET", request);

			request.setCharacterEncoding("UTF-8");
			response.setContentType("application/json; charset=utf-8");

			SecurityContext securityContext = getSecurityContext(request);

			// set default value for property view
			propertyView.set(defaultPropertyView);

			// evaluate constraints and measure query time
			double queryTimeStart                 = System.nanoTime();
			ResourceConstraint resourceConstraint = addSortingAndPaging(request, securityContext, optimizeConstraintChain(parsePath(securityContext, request)));
			double queryTimeEnd                   = System.nanoTime();

			// create result set
			Result result = new Result(resourceConstraint.doGet(), resourceConstraint.isCollectionResource());
			if (result != null) {

				DecimalFormat decimalFormat = new DecimalFormat("0.000000000", DecimalFormatSymbols.getInstance(Locale.ENGLISH));

				result.setQueryTime(decimalFormat.format((queryTimeEnd - queryTimeStart) / 1000000000.0));

				Writer writer = response.getWriter();

				gson.toJson(result, writer);
				writer.append("\n"); // useful newline
				writer.flush();
				writer.close();
				response.setStatus(HttpServletResponse.SC_OK);

			} else {

				logger.log(Level.WARNING, "Result was null!");

				int code = HttpServletResponse.SC_NO_CONTENT;

				response.setStatus(code);
				response.getWriter().append(jsonError(code, "Result was null!"));
				response.getWriter().flush();
				response.getWriter().close();

			}

		} catch (MessageException msgException) {

			int code = msgException.getStatus();

			response.setStatus(code);
			response.getWriter().append(jsonMsg(msgException.getMessage()));
			response.getWriter().flush();
			response.getWriter().close();

		} catch (FrameworkException frameworkException) {

			// set status & write JSON output
			response.setStatus(frameworkException.getStatus());
			gson.toJson(frameworkException, response.getWriter());

			response.getWriter().flush();
			response.getWriter().close();

		} catch (JsonSyntaxException jsex) {

			logger.log(Level.WARNING, "JsonSyntaxException in GET", jsex);

			int code = HttpServletResponse.SC_BAD_REQUEST;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in GET: " + jsex.getMessage()));

		} catch (JsonParseException jpex) {

			logger.log(Level.WARNING, "JsonParseException in GET", jpex);

			int code = HttpServletResponse.SC_BAD_REQUEST;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in GET: " + jpex.getMessage()));

		} catch (Throwable t) {

			logger.log(Level.WARNING, "Exception in GET", t);

			int code = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in GET: " + t.getMessage()));
		}
	}

	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="HEAD">
	@Override
	protected void doHead(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

		try {
			logRequest("HEAD", request);

			request.setCharacterEncoding("UTF-8");
			response.setContentType("application/json; charset=UTF-8");

			SecurityContext securityContext       = getSecurityContext(request);
			List<ResourceConstraint> chain        = parsePath(securityContext, request);
			ResourceConstraint resourceConstraint = optimizeConstraintChain(chain);
			RestMethodResult result               = resourceConstraint.doHead();

			result.commitResponse(gson, response);

		} catch (IllegalArgumentException illegalArgumentException) {
			handleValidationError(illegalArgumentException, response);
		} catch (FrameworkException frameworkException) {

			int code = frameworkException.getStatus();

			response.setStatus(code);
			response.getWriter().append(jsonError(code, frameworkException.getMessage()));

		} catch (JsonSyntaxException jsex) {

			logger.log(Level.WARNING, "JsonSyntaxException in HEAD", jsex);

			int code = HttpServletResponse.SC_BAD_REQUEST;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in HEAD: " + jsex.getMessage()));

		} catch (JsonParseException jpex) {

			logger.log(Level.WARNING, "JsonParseException in HEAD", jpex);

			int code = HttpServletResponse.SC_BAD_REQUEST;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in HEAD: " + jpex.getMessage()));

		} catch (Throwable t) {

			logger.log(Level.WARNING, "Exception in HEAD", t);

			int code = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in HEAD: " + t.getMessage()));
		}
	}

	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="OPTIONS">
	@Override
	protected void doOptions(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

		try {
			logRequest("OPTIONS", request);

			request.setCharacterEncoding("UTF-8");
			response.setContentType("application/json; charset=UTF-8");

			SecurityContext securityContext       = getSecurityContext(request);
			List<ResourceConstraint> chain        = parsePath(securityContext, request);
			ResourceConstraint resourceConstraint = optimizeConstraintChain(chain);
			RestMethodResult result               = resourceConstraint.doOptions();

			result.commitResponse(gson, response);

		} catch (IllegalArgumentException illegalArgumentException) {
			handleValidationError(illegalArgumentException, response);
		} catch (FrameworkException frameworkException) {

			int code = frameworkException.getStatus();

			response.setStatus(code);
			response.getWriter().append(jsonError(code, frameworkException.getMessage()));

		} catch (JsonSyntaxException jsex) {

			logger.log(Level.WARNING, "JsonSyntaxException in OPTIONS", jsex);

			int code = HttpServletResponse.SC_BAD_REQUEST;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in OPTIONS: " + jsex.getMessage()));

		} catch (JsonParseException jpex) {

			logger.log(Level.WARNING, "JsonParseException in OPTIONS", jpex);

			int code = HttpServletResponse.SC_BAD_REQUEST;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in OPTIONS: " + jpex.getMessage()));

		} catch (Throwable t) {

			logger.log(Level.WARNING, "Exception in OPTIONS", t);

			int code = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in OPTIONS: " + t.getMessage()));
		}
	}

	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="POST">
	@Override
	protected void doPost(final HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

		try {
			logRequest("POST", request);

			request.setCharacterEncoding("UTF-8");
			response.setContentType("application/json; charset=UTF-8");

			final PropertySet propertySet   = gson.fromJson(request.getReader(), PropertySet.class);
			SecurityContext securityContext = getSecurityContext(request);
			if(securityContext != null) {

				// evaluate constraint chain
				List<ResourceConstraint> chain        = parsePath(securityContext, request);
				ResourceConstraint resourceConstraint = optimizeConstraintChain(chain);
				Map<String, Object> properties        = new LinkedHashMap<String, Object>();

				// copy properties to map
				if(propertySet != null) {
					for (NodeAttribute attr : propertySet.getAttributes()) {
						properties.put(attr.getKey(), attr.getValue());
					}
				}

				// do action
				RestMethodResult result = resourceConstraint.doPost(properties);

				// set default value for property view
				propertyView.set(defaultPropertyView);

				// commit response
				result.commitResponse(gson, response);

			} else {

				RestMethodResult result = new RestMethodResult(HttpServletResponse.SC_FORBIDDEN);
				result.commitResponse(gson, response);
			}

		} catch (IllegalArgumentException illegalArgumentException) {
			handleValidationError(illegalArgumentException, response);
		} catch (FrameworkException frameworkException) {

			// set status & write JSON output
			response.setStatus(frameworkException.getStatus());
			gson.toJson(frameworkException, response.getWriter());

			response.getWriter().flush();
			response.getWriter().close();

		} catch (JsonSyntaxException jsex) {

			logger.log(Level.WARNING, "JsonSyntaxException in POST", jsex);

			int code = HttpServletResponse.SC_BAD_REQUEST;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in POST: " + jsex.getMessage()));

		} catch (JsonParseException jpex) {

			logger.log(Level.WARNING, "JsonParseException in POST", jpex);

			int code = HttpServletResponse.SC_BAD_REQUEST;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonParseException in POST: " + jpex.getMessage()));

		} catch (UnsupportedOperationException uoe) {

			logger.log(Level.WARNING, "POST not supported", uoe);

			int code = HttpServletResponse.SC_BAD_REQUEST;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "POST not supported: " + uoe.getMessage()));

		} catch (Throwable t) {

			logger.log(Level.WARNING, "Exception in POST", t);

			int code = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in POST: " + t.getMessage()));
		}
	}

	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="PUT">
	@Override
	protected void doPut(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {

		try {
			logRequest("PUT", request);

			request.setCharacterEncoding("UTF-8");
			response.setContentType("application/json; charset=UTF-8");

			final PropertySet propertySet   = gson.fromJson(request.getReader(), PropertySet.class);
			
			SecurityContext securityContext = getSecurityContext(request);
			if(securityContext != null) {

				// evaluate constraint chain
				List<ResourceConstraint> chain        = parsePath(securityContext, request);
				ResourceConstraint resourceConstraint = optimizeConstraintChain(chain);

				// create Map with properties
				Map<String, Object> properties = new LinkedHashMap<String, Object>();
				for (NodeAttribute attr : propertySet.getAttributes()) {
					properties.put(attr.getKey(), attr.getValue());

				}

				// do action
				RestMethodResult result = resourceConstraint.doPut(properties);
				result.commitResponse(gson, response);

			} else {

				RestMethodResult result = new RestMethodResult(HttpServletResponse.SC_FORBIDDEN);
				result.commitResponse(gson, response);
			}

		} catch (IllegalArgumentException illegalArgumentException) {
			handleValidationError(illegalArgumentException, response);
		} catch (FrameworkException frameworkException) {

			int code = frameworkException.getStatus();

			response.setStatus(code);
			response.getWriter().append(jsonError(code, frameworkException.getMessage()));

		} catch (JsonSyntaxException jsex) {

			logger.log(Level.WARNING, "JsonSyntaxException in PUT", jsex);

			int code = HttpServletResponse.SC_BAD_REQUEST;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in PUT: " + jsex.getMessage()));

		} catch (JsonParseException jpex) {

			logger.log(Level.WARNING, "JsonParseException in PUT", jpex);

			int code = HttpServletResponse.SC_BAD_REQUEST;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in PUT: " + jpex.getMessage()));

		} catch (Throwable t) {

			logger.log(Level.WARNING, "Exception in PUT", t);

			int code = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

			response.setStatus(code);
			response.getWriter().append(jsonError(code, "JsonSyntaxException in PUT: " + t.getMessage()));
		}
	}

	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="TRACE">
	@Override
	protected void doTrace(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

		logRequest("TRACE", request);

		response.setContentType("application/json; charset=UTF-8");

		int code = HttpServletResponse.SC_METHOD_NOT_ALLOWED;

		response.setStatus(code);
		response.getWriter().append(jsonError(code, "TRACE method not allowed"));
	}

	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="private methods">
	private List<ResourceConstraint> parsePath(SecurityContext securityContext, HttpServletRequest request) throws FrameworkException {

		String path = request.getPathInfo();

		// intercept empty path and send 204 No Content
		if (!StringUtils.isNotBlank(path)) {

			throw new NoResultsException();

		}

		// 1.: split request path into URI parts
		String[] pathParts = path.split("[/]+");

		// 2.: create container for resource constraints
		List<ResourceConstraint> constraintChain = new ArrayList<ResourceConstraint>(pathParts.length);

		// 3.: try to assign resource constraints for each URI part
		for (int i = 0; i < pathParts.length; i++) {

			// eliminate empty strings
			String part = pathParts[i].trim();

			if (part.length() > 0) {
				
				boolean found = false;

				// look for matching pattern
				for (Entry<Pattern, Class> entry : constraintMap.entrySet()) {

					Pattern pattern = entry.getKey();
					Matcher matcher = pattern.matcher(pathParts[i]);

					if (matcher.matches()) {

						try {

							Class type = entry.getValue();

							// instantiate resource constraint
							ResourceConstraint constraint = (ResourceConstraint) type.newInstance();

							// set security context
							constraint.setSecurityContext(securityContext);

							if (constraint.checkAndConfigure(part, securityContext, request)) {

								logger.log(Level.FINE, "{0} matched, adding constraint of type {1} for part {2}",
									   new Object[] { matcher.pattern(),
											  type.getName(), part });



								// allow constraint to modify context
								constraint.configurePropertyView(propertyView);

								// add constraint and go on
								constraintChain.add(constraint);

								found = true;
								// first match wins, so choose priority wisely ;)
								break;

							}

						} catch (Throwable t) {
							logger.log(Level.WARNING, "Error instantiating constraint class", t);
						}

					}
				}
				
				if(!found) {
					throw new IllegalPathException();
				}
			}
		}

		return constraintChain;
	}

	private ResourceConstraint optimizeConstraintChain(List<ResourceConstraint> constraintChain) throws FrameworkException {

		int num        = constraintChain.size();
		boolean found  = false;
		int iterations = 0;

		do {

			StringBuilder chain = new StringBuilder();

			for (ResourceConstraint constr : constraintChain) {

				chain.append(constr.getClass().getSimpleName());
				chain.append(", ");

			}

			logger.log(Level.FINE, "########## Constraint chain after iteration {0}: {1}", new Object[] { iterations, chain.toString() });

			found = false;

			for (int i = 0; i < num; i++) {

				try {
					ResourceConstraint firstElement       = constraintChain.get(i);
					ResourceConstraint secondElement      = constraintChain.get(i + 1);
					ResourceConstraint combinedConstraint = firstElement.tryCombineWith(secondElement);

					if (combinedConstraint != null) {

						logger.log(Level.FINE, "Combined constraint {0}", combinedConstraint.getClass().getSimpleName());

						// remove source constraints
						constraintChain.remove(firstElement);
						constraintChain.remove(secondElement);

						// add combined constraint
						constraintChain.add(i, combinedConstraint);

						// signal success
						found = true;

						//
						if (combinedConstraint instanceof RelationshipFollowingConstraint) {

							break;

						}

					}

				} catch(Throwable t) {
					// ignore exceptions thrown here
				}
			}

			iterations++;

		} while (found);

		StringBuilder chain = new StringBuilder();

		for (ResourceConstraint constr : constraintChain) {

			chain.append(constr.getClass().getSimpleName());
			chain.append(", ");

		}

		logger.log(Level.FINE, "Final constraint chain {0}", chain.toString());

		if (constraintChain.size() == 1) {

			ResourceConstraint finalConstraint = constraintChain.get(0);

			// inform final constraint about the configured ID property
			finalConstraint.configureIdProperty(defaultIdProperty);

			return finalConstraint;

		}

		throw new IllegalPathException();
	}

	private PropertyFormat initializePropertyFormat() {

		// ----- set property format from init parameters -----
		String propertyFormatParameter = this.getInitParameter(SERVLET_PARAMETER_PROPERTY_FORMAT);
		PropertyFormat propertyFormat  = PropertyFormat.NestedKeyValueType;

		if (propertyFormatParameter != null) {

			try {

				propertyFormat = PropertyFormat.valueOf(propertyFormatParameter);

				logger.log(Level.INFO, "Setting property format to {0}", propertyFormatParameter);

			} catch (Throwable t) {
				logger.log(Level.WARNING, "Cannot use property format {0}, unknown format.", propertyFormatParameter);
			}

		}

		return propertyFormat;
	}

	private void handleValidationError(IllegalArgumentException illegalArgumentException, HttpServletResponse response) {

		// illegal state exception, return error
		StringBuilder errorBuffer = new StringBuilder(100);

		errorBuffer.append(illegalArgumentException.getMessage());

		final int code = HttpServletResponse.SC_BAD_REQUEST;

		// send response
		response.setStatus(code);
		response.setContentType("application/json; charset=UTF-8");

		try {

			response.getWriter().append(jsonError(code, errorBuffer.toString()));
			response.getWriter().flush();
			response.getWriter().close();

		} catch (Throwable t) {
			logger.log(Level.WARNING, "Unable to commit response", t);
		}
	}

	private ResourceConstraint addSortingAndPaging(HttpServletRequest request, SecurityContext securityContext, ResourceConstraint finalConstraint) throws FrameworkException {

		ResourceConstraint pagedSortedConstraint = finalConstraint;

		// sorting
		String sortKey = request.getParameter(REQUEST_PARAMETER_SORT_KEY);
		if (sortKey != null) {

			String sortOrder = request.getParameter(REQUEST_PARAMETER_SORT_ORDER);
			if (sortOrder == null) {
				sortOrder = DEFAULT_VALUE_SORT_ORDER;
			}

			// combine sort constraint
			pagedSortedConstraint = pagedSortedConstraint.tryCombineWith(new SortConstraint(securityContext, sortKey, sortOrder));

		}

		// paging
		String pageSizeParameter = request.getParameter(REQUEST_PARAMETER_PAGE_SIZE);

		if (pageSizeParameter != null) {

			String pageParameter = request.getParameter(REQUEST_PARAMETER_PAGE_NUMBER);
			int pageSize         = parseInt(pageSizeParameter, DEFAULT_VALUE_PAGE_SIZE);
			int page             = parseInt(pageParameter, 1);

			if (pageSize <= 0) {
				throw new IllegalPathException();
			}

			pagedSortedConstraint = pagedSortedConstraint.tryCombineWith(new PagingConstraint(securityContext, page, pageSize));

		}

		return pagedSortedConstraint;
	}

	/**
	 * Tries to parse the given String to an int value, returning
	 * defaultValue on error.
	 *
	 * @param value the source String to parse
	 * @param defaultValue the default value that will be returned when parsing fails
	 * @return the parsed value or the given default value when parsing fails
	 */
	private int parseInt(String value, int defaultValue) {

		if (value == null) {

			return defaultValue;

		}

		try {
			return Integer.parseInt(value);
		} catch (Throwable ignore) {}

		return defaultValue;
	}

	private String jsonError(final int code, final String message) {

		StringBuilder buf = new StringBuilder(100);

		buf.append("{\n");
		buf.append("    \"error\" : {\n");
		buf.append("        \"code\" : ").append(code);

		if(message != null) {
			buf.append(",\n        \"message\" : \"").append(message).append("\"\n");
		} else {
			buf.append("\n");
		}

		buf.append("    }\n");
		buf.append("}\n");

		return buf.toString();
	}

	private String jsonMsg(final String message) {

		StringBuilder buf = new StringBuilder(100);

		buf.append("{\n");

		if(message != null) {
			buf.append("    \"message\" : \"").append(message).append("\"\n");
		} else {
			buf.append("    \"message\" : \"\"\n");
		}

		buf.append("}\n");

		return buf.toString();
	}

	private SecurityContext getSecurityContext(HttpServletRequest request) {

		// return SecurityContext.getSuperUserInstance();
		return SecurityContext.getInstance(this.getServletConfig(), request, AccessMode.Frontend);
	}

	private void logRequest(String method, HttpServletRequest request) {
//
//		if(logWriter != null) {
//
//			try {
//				logWriter.append(accessLogDateFormat.format(System.currentTimeMillis()));
//				logWriter.append(" ");
//				logWriter.append(StringUtils.rightPad(method, 8));
//				logWriter.append(request.getRequestURI());
//				logWriter.append("\n");
//
//				BufferedReader reader = request.getReader();
//				if(reader.markSupported()) {
//					reader.mark(65535);
//				}
//
//				String line = reader.readLine();
//				while(line != null) {
//					logWriter.append("        ");
//					logWriter.append(line);
//					line = reader.readLine();
//					logWriter.append("\n");
//				}
//
//				reader.reset();
//
//				logWriter.flush();
//				
//			} catch(IOException ioex) {
//				// ignore
//			}
//		}
	}
	// </editor-fold>

	//~--- inner classes --------------------------------------------------

	// <editor-fold defaultstate="collapsed" desc="nested classes">
	private class ThreadLocalPropertyView extends ThreadLocal<String> implements Value<String> {

		@Override
		protected String initialValue() {
			return defaultPropertyView;
		}
	}

	// </editor-fold>
}