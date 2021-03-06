/*
 *  Copyright (C) 2010-2013 Axel Morgner
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



package org.structr.rest.resource;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.structr.core.property.PropertyKey;
import org.structr.common.SecurityContext;
import org.structr.common.error.FrameworkException;
import org.structr.common.error.TypeToken;
import org.structr.core.Adapter;
import org.structr.core.EntityContext;
import org.structr.core.GraphObject;
import org.structr.core.Result;
import org.structr.core.Services;
import org.structr.core.entity.AbstractNode;
import org.structr.core.entity.AbstractRelationship;
import org.structr.core.graph.DeleteRelationshipCommand;
import org.structr.core.graph.NodeFactory;
import org.structr.core.graph.StructrTransaction;
import org.structr.core.graph.TransactionCommand;
import org.structr.core.graph.search.Search;
import org.structr.core.graph.search.SearchAttribute;
import org.structr.core.graph.search.SearchNodeCommand;
import org.structr.core.notion.Notion;
import org.structr.core.property.AbstractRelationProperty;
import org.structr.rest.RestMethodResult;
import org.structr.rest.exception.IllegalPathException;
import org.structr.rest.exception.NotFoundException;
import org.structr.rest.exception.SystemException;
//~--- JDK imports ------------------------------------------------------------

//~--- classes ----------------------------------------------------------------

/**
 *
 * @author Christian Morgner
 */
public class StaticRelationshipResource extends SortableResource {

	private static final Logger logger = Logger.getLogger(StaticRelationshipResource.class.getName());

	//~--- fields ---------------------------------------------------------

	TypeResource typeResource       = null;
	TypedIdResource typedIdResource = null;

	//~--- constructors ---------------------------------------------------constructors

	public StaticRelationshipResource(final SecurityContext securityContext, final TypedIdResource typedIdResource, final TypeResource typeResource) {

		this.securityContext = securityContext;
		this.typedIdResource = typedIdResource;
		this.typeResource    = typeResource;
	}

	//~--- methods --------------------------------------------------------

	@Override
	public Result doGet(final PropertyKey sortKey, final boolean sortDescending, final int pageSize, final int page, final String offsetId) throws FrameworkException {

		// ok, source node exists, fetch it
		final AbstractNode sourceNode = typedIdResource.getTypesafeNode();
		if (sourceNode != null) {

			final PropertyKey key = findPropertyKey(typedIdResource, typeResource);
			if (key != null) {

				final Object value = sourceNode.getProperty(key);
				if (value != null) {

					if (value instanceof List) {

						final List<GraphObject> list = (List<GraphObject>)value;
						applyDefaultSorting(list, sortKey, sortDescending);

						//return new Result(list, null, isCollectionResource(), isPrimitiveArray());
						return new Result(PagingHelper.subList(list, pageSize, page, offsetId), list.size(), isCollectionResource(), isPrimitiveArray());

					} else if (value instanceof Iterable) {

						// check type of value (must be an Iterable of GraphObjects in order to proceed here)
						final List<GraphObject> propertyListResult = new LinkedList<GraphObject>();
						final Iterable sourceIterable              = (Iterable) value;

						for (final Object o : sourceIterable) {

							if (o instanceof GraphObject) {

								propertyListResult.add((GraphObject) o);
							}
						}

						applyDefaultSorting(propertyListResult, sortKey, sortDescending);

						//return new Result(propertyListResult, null, isCollectionResource(), isPrimitiveArray());
						return new Result(PagingHelper.subList(propertyListResult, pageSize, page, offsetId), propertyListResult.size(), isCollectionResource(), isPrimitiveArray());

					}
				}

			}
		}

		return Result.EMPTY_RESULT;
	}

	@Override
	public RestMethodResult doPut(final Map<String, Object> propertySet) throws FrameworkException {

		final List<? extends GraphObject> results = typedIdResource.doGet(null, false, NodeFactory.DEFAULT_PAGE_SIZE, NodeFactory.DEFAULT_PAGE, null).getResults();
		final SearchNodeCommand searchNode        = Services.command(securityContext, SearchNodeCommand.class);

		if (results != null) {

			// fetch static relationship definition
			final PropertyKey key = findPropertyKey(typedIdResource, typeResource);
			if (key != null && key instanceof AbstractRelationProperty) {

				final AbstractRelationProperty staticRel = (AbstractRelationProperty)key;
				final AbstractNode startNode             = typedIdResource.getTypesafeNode();

				if (startNode != null) {

					Class startNodeType = startNode.getClass();
					
					//if (EntityContext.isReadOnlyProperty(startNodeType, EntityContext.getPropertyKeyForName(startNodeType, typeResource.getRawType()))) {
					if (EntityContext.getPropertyKeyForJSONName(startNodeType, typeResource.getRawType()).isReadOnlyProperty()) {

						logger.log(Level.INFO, "Read-only property on {1}: {0}", new Object[] { startNode.getClass(), typeResource.getRawType() });

						return new RestMethodResult(HttpServletResponse.SC_FORBIDDEN);

					}

					final DeleteRelationshipCommand deleteRel = Services.command(securityContext, DeleteRelationshipCommand.class);
					final List<AbstractRelationship> rels     = startNode.getRelationships(staticRel.getRelType(), staticRel.getDirection());
					final StructrTransaction transaction      = new StructrTransaction() {

						@Override
						public Object execute() throws FrameworkException {

							for (final AbstractRelationship rel : rels) {

								final AbstractNode otherNode = rel.getOtherNode(startNode);
								final Class otherNodeType    = otherNode.getClass();
								final String id              = otherNode.getProperty(AbstractNode.uuid);

								// Delete relationship only if not contained in property set
								// check type of other node as well, there can be relationships
								// of the same type to more than one destTypes!
								if (staticRel.getDestType().equals(otherNodeType) &&!propertySet.containsValue(id)) {

									deleteRel.execute(rel);

								} else {

									// Remove id from set because there's already an existing relationship
									propertySet.values().remove(id);
								}

							}

							// Now add new relationships for any new id: This should be the rest of the property set
							for (final Object obj : propertySet.values()) {

								final String uuid                 = (String) obj;
								final List<SearchAttribute> attrs = new LinkedList<SearchAttribute>();

								attrs.add(Search.andExactUuid(uuid));

								final Result results = searchNode.execute(attrs);

								if (results.isEmpty()) {

									throw new NotFoundException();

								}

								if (results.size() > 1) {

									throw new SystemException("More than one result found for uuid " + uuid + "!");

								}

								final AbstractNode targetNode = (AbstractNode) results.get(0);

//                                                              String type             = EntityContext.normalizeEntityName(typeResource.getRawType());
								final Class type = staticRel.getDestType();

								if (!type.equals(targetNode.getClass())) {

									throw new FrameworkException(startNode.getClass().getSimpleName(), new TypeToken(AbstractNode.uuid, type.getSimpleName()));

								}

								staticRel.createRelationship(securityContext, startNode, targetNode);

							}

							return null;
						}
					};

					// execute transaction
					Services.command(securityContext, TransactionCommand.class).execute(transaction);

				}

			}
		}

		return new RestMethodResult(HttpServletResponse.SC_OK);
	}

	@Override
	public RestMethodResult doPost(final Map<String, Object> propertySet) throws FrameworkException {

		// create transaction closure
		final StructrTransaction transaction = new StructrTransaction() {

			@Override
			public Object execute() throws FrameworkException {

				final AbstractNode sourceNode = typedIdResource.getIdResource().getNode();
				final PropertyKey propertyKey = findPropertyKey(typedIdResource, typeResource);
				
				if (sourceNode != null && propertyKey != null) {

					if (propertyKey instanceof AbstractRelationProperty) {
						
						final AbstractRelationProperty relationshipProperty = (AbstractRelationProperty)propertyKey;
						final Class sourceNodeType                          = sourceNode.getClass();

						if (relationshipProperty.isReadOnlyProperty()) {

							logger.log(Level.INFO, "Read-only property on {0}: {1}", new Object[] { sourceNodeType, typeResource.getRawType() });

							return null;
						}

						// fetch notion
						final Notion notion                  = relationshipProperty.getNotion();
						final PropertyKey primaryPropertyKey = notion.getPrimaryPropertyKey();

						// apply notion if the property set contains the ID property as the only element
						if (primaryPropertyKey != null && propertySet.containsKey(primaryPropertyKey.jsonName()) && propertySet.size() == 1) {

							// the notion that is defined for this relationship can deserialize
							// objects with a single key (uuid for example), and the POSTed
							// property set contains value(s) for this key, so we only need
							// to create relationships
							final Adapter<Object, GraphObject> deserializationStrategy = notion.getAdapterForSetter(securityContext);
							final Object keySource                                     = propertySet.get(primaryPropertyKey.jsonName());

							if (keySource != null) {

								GraphObject otherNode = null;

								if (keySource instanceof Collection) {

									final Collection collection = (Collection) keySource;

									for (final Object key : collection) {

										otherNode = deserializationStrategy.adapt(key);

										if (otherNode != null && otherNode instanceof AbstractNode) {

											relationshipProperty.createRelationship(securityContext, sourceNode, (AbstractNode)otherNode);

										} else {

											logger.log(Level.WARNING, "Relationship end node has invalid type {0}", otherNode.getClass().getName());
										}

									}

								} else {

									// create a single relationship
									otherNode = deserializationStrategy.adapt(keySource);

									if (otherNode != null && otherNode instanceof AbstractNode) {

										relationshipProperty.createRelationship(securityContext, sourceNode, (AbstractNode)otherNode);

									} else {

										logger.log(Level.WARNING, "Relationship end node has invalid type {0}", otherNode.getClass().getName());

									}
								}

								return otherNode;

							} else {

								logger.log(Level.INFO, "Key {0} not found in {1}", new Object[] { primaryPropertyKey.jsonName(), propertySet.toString() });

							}

							return null;

						} else {

							// the notion can not deserialize objects with a single key, or
							// the POSTed propertySet did not contain a key to deserialize,
							// so we create a new node from the POSTed properties and link
							// the source node to it. (this is the "old" implementation)
							final AbstractNode otherNode = typeResource.createNode(propertySet);

							// FIXME: this prevents post creation transformations from working
							// properly if they rely on an already existing relationship when
							// the transformation runs.

							// TODO: we need to find a way to notify the listener at the end of the
							// transaction, when all entities and relationships are created!
							if (otherNode != null) {

								// FIXME: this creates duplicate relationships when the related
								//        node ID is already present in the property set..



								relationshipProperty.createRelationship(securityContext, sourceNode, otherNode);

								return otherNode;

							}
						}
					}
				}

				throw new IllegalPathException();
			}
		};

		// execute transaction: create new node
		final AbstractNode newNode = (AbstractNode) Services.command(securityContext, TransactionCommand.class).execute(transaction);
		RestMethodResult result;

		if (newNode != null) {

			result = new RestMethodResult(HttpServletResponse.SC_CREATED);

			result.addHeader("Location", buildLocationHeader(newNode));

		} else {

			result = new RestMethodResult(HttpServletResponse.SC_FORBIDDEN);

		}

		return result;
	}

	@Override
	public RestMethodResult doHead() throws FrameworkException {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public RestMethodResult doOptions() throws FrameworkException {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public boolean checkAndConfigure(final String part, final SecurityContext securityContext, final HttpServletRequest request) {
		return false;
	}

	@Override
	public Resource tryCombineWith(final Resource next) throws FrameworkException {

		if (next instanceof TypeResource) {

			throw new IllegalPathException();

		}

		return super.tryCombineWith(next);
	}

	//~--- get methods ----------------------------------------------------

	@Override
	public Class getEntityClass() {
		return typeResource.getEntityClass();
	}

	@Override
	public String getUriPart() {
		return typedIdResource.getUriPart().concat("/").concat(typeResource.getUriPart());
	}

	public TypedIdResource getTypedIdConstraint() {
		return typedIdResource;
	}

	public TypeResource getTypeConstraint() {
		return typeResource;
	}

	@Override
	public boolean isCollectionResource() {
		return true;
	}

        @Override
        public String getResourceSignature() {
                return typedIdResource.getResourceSignature().concat("/").concat(typeResource.getResourceSignature());
        }
}
