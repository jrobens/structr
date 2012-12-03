/*
 *  Copyright (C) 2012 Axel Morgner
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



package org.structr.common.property;

import org.structr.common.SecurityContext;
import org.structr.core.GraphObject;
import org.structr.core.converter.PropertyConverter;
import org.structr.core.converter.RelationshipStartNodeConverter;

//~--- classes ----------------------------------------------------------------

/**
 *
 * @author Axel Morgner
 */
public class StartNodeProperty<T> extends Property<T> {

	public StartNodeProperty(String name) {

		super(name);

	}

	//~--- methods --------------------------------------------------------

	@Override
	public PropertyConverter<T, ?> databaseConverter(SecurityContext securityContext, GraphObject entity) {

		return new RelationshipStartNodeConverter(securityContext, entity);

	}

	@Override
	public PropertyConverter<?, T> inputConverter(SecurityContext securityContext) {

		return null;

	}

	@Override
	public Object fixDatabaseProperty(Object value) {

		return null;

	}

	@Override
	public String typeName() {

		return null;

	}

}