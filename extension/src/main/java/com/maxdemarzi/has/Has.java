package com.maxdemarzi.has;

import com.maxdemarzi.CustomObjectMapper;
import com.maxdemarzi.attributes.Attributes;
import com.maxdemarzi.schema.RelationshipTypes;
import com.maxdemarzi.users.Users;
import com.maxdemarzi.wants.Wants;
import org.codehaus.jackson.map.ObjectMapper;
import org.neo4j.graphdb.*;

import javax.ws.rs.*;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.*;

import static com.maxdemarzi.Time.utc;
import static com.maxdemarzi.schema.Properties.*;
import static java.util.Collections.reverseOrder;

@Path("/users/{username}/has")
public class Has {

    private static final ObjectMapper objectMapper = CustomObjectMapper.getInstance();
    private static final Comparator<Map<String, Object>> sharedComparator = Comparator.comparing(m -> (Boolean)m.get(WANT));
    private static final Comparator<Map<String, Object>> timedComparator = Comparator.comparing(m -> (ZonedDateTime) m.get(TIME), reverseOrder());

    @GET
    public Response getHas(@PathParam("username") final String username,
                             @QueryParam("limit") @DefaultValue("25") final Integer limit,
                             @QueryParam("offset") @DefaultValue("0")  final Integer offset,
                             @QueryParam("username2") final String username2,
                             @Context GraphDatabaseService db) throws IOException {
        ArrayList<Map<String, Object>> results = new ArrayList<>();

        try (Transaction tx = db.beginTx()) {
            Node user = Users.findUser(username, db);
            Node user2;
            HashSet<Node> user2Has = new HashSet<>();
            HashSet<Node> user2Wants = new HashSet<>();
            if (username2 != null) {
                user2 = Users.findUser(username2, db);
                for (Relationship r1 : user2.getRelationships(Direction.OUTGOING, RelationshipTypes.HAS)) {
                    user2Has.add(r1.getEndNode());
                }
                for (Relationship r1 : user2.getRelationships(Direction.OUTGOING, RelationshipTypes.WANTS)) {
                    user2Wants.add(r1.getEndNode());
                }
            }
            for (Relationship r1 : user.getRelationships(Direction.OUTGOING, RelationshipTypes.HAS)) {
                Node attribute = r1.getEndNode();
                Map<String, Object> properties = attribute.getAllProperties();
                properties.put(TIME, r1.getProperty("time"));
                properties.put(HAVE, user2Has.contains(attribute));
                properties.put(WANT, user2Wants.contains(attribute));
                properties.put(WANTS, attribute.getDegree(RelationshipTypes.WANTS, Direction.INCOMING));
                properties.put(HAS, attribute.getDegree(RelationshipTypes.HAS, Direction.INCOMING));
                results.add(properties);
            }
            tx.success();
        }

        results.sort(sharedComparator.thenComparing(timedComparator));

        if (offset > results.size()) {
            return Response.ok().entity(objectMapper.writeValueAsString(
                    results.subList(0, 0)))
                    .build();
        } else {
            return Response.ok().entity(objectMapper.writeValueAsString(
                    results.subList(offset, Math.min(results.size(), limit + offset))))
                    .build();
        }
    }

    @POST
    @Path("/{name}")
    public Response createHas(@PathParam("username") final String username,
                               @PathParam("name") final String name,
                               @Context GraphDatabaseService db) throws IOException {
        Map<String, Object> results;

        try (Transaction tx = db.beginTx()) {
            Node user = Users.findUser(username, db);
            Node attribute = Attributes.findAttribute(name, db);

            if (userHasAttribute(user, attribute)) {
                throw HasExceptions.alreadyHasAttribute;
            }

            Relationship has = user.createRelationshipTo(attribute, RelationshipTypes.HAS);
            has.setProperty(TIME, ZonedDateTime.now(utc));
            results = attribute.getAllProperties();
            results.put(HAVE, true);
            results.put(WANT, Wants.userWantsAttribute(user, attribute));
            results.put(HAS, attribute.getDegree(RelationshipTypes.HAS, Direction.INCOMING));
            results.put(WANTS, attribute.getDegree(RelationshipTypes.WANTS, Direction.INCOMING));
            tx.success();
        }
        return Response.ok().entity(objectMapper.writeValueAsString(results)).build();
    }

    @DELETE
    @Path("/{name}")
    public Response removeHas(@PathParam("username") final String username,
                               @PathParam("name") final String name,
                               @Context GraphDatabaseService db) throws IOException {
        boolean has = false;
        try (Transaction tx = db.beginTx()) {
            Node user = Users.findUser(username, db);
            Node attribute = Attributes.findAttribute(name, db);

            if (user.getDegree(RelationshipTypes.HAS, Direction.OUTGOING)
                    < attribute.getDegree(RelationshipTypes.HAS, Direction.INCOMING) ) {
                for (Relationship r1 : user.getRelationships(Direction.OUTGOING, RelationshipTypes.HAS)) {
                    if (r1.getEndNode().equals(attribute)) {
                        r1.delete();
                        has = true;
                        break;
                    }
                }
            } else {
                for (Relationship r1 : attribute.getRelationships(Direction.INCOMING, RelationshipTypes.HAS)) {
                    if (r1.getStartNode().equals(user)) {
                        r1.delete();
                        has = true;
                        break;
                    }
                }
            }
            tx.success();
        }

        if(!has) {
            throw HasExceptions.notHavingAttribute;
        }

        return Response.noContent().build();
    }

    public static boolean userHasAttribute(Node user, Node attribute) {
        if (user == null) {
            return false;
        }

        boolean alreadyHave = false;
        if (user.getDegree(RelationshipTypes.HAS, Direction.OUTGOING)
                < attribute.getDegree(RelationshipTypes.HAS, Direction.INCOMING) ) {
            for (Relationship r1 : user.getRelationships(Direction.OUTGOING, RelationshipTypes.HAS)) {
                if (r1.getEndNode().equals(attribute)) {
                    alreadyHave = true;
                    break;
                }
            }
        } else {
            for (Relationship r1 : attribute.getRelationships(Direction.INCOMING, RelationshipTypes.HAS)) {
                if (r1.getStartNode().equals(user)) {
                    alreadyHave = true;
                    break;
                }
            }
        }
        return alreadyHave;
    }
}
