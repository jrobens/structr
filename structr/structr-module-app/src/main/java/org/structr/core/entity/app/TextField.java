/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.structr.core.entity.app;

import org.structr.common.SessionValue;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.lang.StringUtils;
import org.structr.common.SessionContext;
import org.structr.core.entity.AbstractNode;
import org.structr.core.entity.User;

/**
 *
 * @author Christian Morgner
 */
public class TextField extends FormField implements InteractiveNode
{
	private static final Logger logger = Logger.getLogger(TextField.class.getName());

	protected SessionValue<String> errorMessage = null;
	protected SessionValue<String> sessionValue = null;
	private String mappedName = null;

	@Override
	public String getIconSrc()
	{
		return "/images/textfield.png";
	}

	@Override
	public void renderView(final StringBuilder out, final AbstractNode startNode, final String editUrl, final Long editNodeId, final User user)
	{
		// if this page is requested to be edited, render edit frame
		if(editNodeId != null && getId() == editNodeId.longValue())
		{

			renderEditFrame(out, editUrl);

			// otherwise, render subnodes in edit mode
		} else
		{

			if(hasTemplate(user))
			{
				template.setCallingNode(this);
				template.renderView(out, startNode, editUrl, editNodeId, user);

			} else
			{
				logger.log(Level.WARNING, "Encountered TextField without template: {0}", this);

				// TODO: default template for TextField?
			}
		}
	}

	// ----- interface InteractiveNode -----
	@Override
	public String getValue()
	{
		HttpServletRequest request = SessionContext.getRequest();
		String valueFromLastRequest = null;
		String name = getName();
		String ret = null;

		// only return value from last request if we were redirected before
		if(SessionContext.isRedirected())
		{
			valueFromLastRequest = getLastValue().get();

		} else
		{
			// otherwise, clear value in session
			getLastValue().set(null);
		}

		if(request == null)
		{
			return valueFromLastRequest;
		}

		if(request != null)
		{
			ret = request.getParameter(name);
			if(ret != null)
			{

				// Parameter is there
				if(ret.length() == 0)
				{
					// Empty value
					return null;

				} else
				{
					// store value in session, in case we get a redirect afterwards
					getLastValue().set(ret);
					return ret;
				}

			} else
			{
				// Parameter is not in request
				return valueFromLastRequest;
			}

		}

		return null;
	}

	@Override
	public String getStringValue()
	{
		Object value = getValue();
		return (value != null ? value.toString() : null);
	}

	@Override
	public Class getParameterType()
	{
		return (String.class);
	}

	@Override
	public void setMappedName(String mappedName)
	{
		this.mappedName = mappedName;
	}

	@Override
	public String getMappedName()
	{
		if(StringUtils.isNotBlank(mappedName))
		{
			return (mappedName);
		}

		return (getName());
	}

	@Override
	public void setErrorValue(Object errorValue)
	{
		getErrorMessageValue().set(errorValue.toString());
	}

	// ----- private methods -----
	private SessionValue<String> getErrorMessageValue()
	{
		if(errorMessage == null)
		{
			errorMessage = new SessionValue<String>(createUniqueIdentifier("errorMessage"));
		}

		return(errorMessage);
	}

	private SessionValue<String> getLastValue()
	{
		if(sessionValue == null)
		{
			sessionValue = new SessionValue<String>(createUniqueIdentifier("lastValue"));
		}

		return(sessionValue);
	}
}