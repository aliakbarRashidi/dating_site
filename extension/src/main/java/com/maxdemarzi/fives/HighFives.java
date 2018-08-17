package com.maxdemarzi.fives;

import com.maxdemarzi.CustomObjectMapper;
import com.maxdemarzi.schema.RelationshipTypes;
import com.maxdemarzi.users.Users;
import org.codehaus.jackson.map.ObjectMapper;
import org.neo4j.graphdb.*;

import javax.ws.rs.*;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.time.*;
import java.util.Map;

import static com.maxdemarzi.Time.dateFormatter;
import static com.maxdemarzi.Time.utc;
import static com.maxdemarzi.schema.Properties.*;
import static com.maxdemarzi.users.Users.getPost;

@Path("/users/{username}/high_fives")
public class HighFives {
    private static final ObjectMapper objectMapper = CustomObjectMapper.getInstance();

    @POST
    @Path("/{username2}/{time}")
    public Response createFive(String body, @PathParam("username") final String username,
                                @PathParam("username2") final String username2,
                                @PathParam("time") final String time,
                                @Context GraphDatabaseService db) throws IOException {

        Map<String, Object> results;
        ZonedDateTime dateTime = ZonedDateTime.now(utc);

        try (Transaction tx = db.beginTx()) {
            Node user = Users.findUser(username, db);

            // Get user's timezone
            ZoneId zoneId;
            String tz = (String)user.getProperty(TIMEZONE, null);
            if (tz == null) {
                Node city = user.getSingleRelationship( RelationshipTypes.IN_LOCATION, Direction.OUTGOING).getEndNode();
                Node state = city.getSingleRelationship( RelationshipTypes.IN_LOCATION, Direction.OUTGOING).getEndNode();
                Node timezone = state.getSingleRelationship( RelationshipTypes.IN_TIMEZONE, Direction.OUTGOING).getEndNode();
                tz = (String)timezone.getProperty(NAME);
                zoneId = ZoneId.of(tz);
                user.setProperty(TIMEZONE, tz);
            } else {
                zoneId = ZoneId.of(tz);
            }

            ZonedDateTime startOfDay = ZonedDateTime.of(dateTime.toLocalDateTime(), zoneId).with(LocalTime.MIN);

            // How many high fives did they receive today on posts within the last 5 days?
            int high5received = 0;
            RelationshipType[] last5days = new RelationshipType[5];
            for (int i = 0; i < 5; i++) {
                last5days[i] = RelationshipType.withName("POSTED_ON_" + dateTime.minusDays(i).format(dateFormatter));
            }
            for (Relationship r1 : user.getRelationships(last5days)) {
                Node post = r1.getEndNode();
                for (Relationship r : post.getRelationships(RelationshipTypes.HIGH_FIVED, Direction.INCOMING)) {
                    ZonedDateTime when = (ZonedDateTime) r.getProperty(TIME);
                    if (when.isAfter(startOfDay)) {
                        high5received++;
                    }
                }
            }

            // How many high fives did they give out today?
            int high5given = 0;
            Node user2 = Users.findUser(username2, db);
            Node post = getPost(user2, ZonedDateTime.parse(time));
            Long postId = post.getId();
            for (Relationship r : user.getRelationships(RelationshipTypes.HIGH_FIVED, Direction.OUTGOING)) {
                if (r.getEndNodeId() == postId) {
                    throw FiveExceptions.alreadyHighFivedPost;
                }
                ZonedDateTime when = (ZonedDateTime)r.getProperty(TIME);
                if (when.isAfter(startOfDay)) {
                    high5given++;
                }
            }

            // Are they over the limit
            if (high5given - 5 >= high5received) {
                throw FiveExceptions.overHighFiveLimit;
            }

            Relationship r2 = user.createRelationshipTo(post, RelationshipTypes.HIGH_FIVED);
            r2.setProperty(TIME, dateTime);

            results = post.getAllProperties();
            results.put(TIME, dateTime);
            results.put(USERNAME, username2);
            results.put(NAME, user2.getProperty(NAME));
            results.put(HIGH_FIVED, true);
            results.put(LOW_FIVED, false);
            results.put(HIGH_FIVES, post.getDegree(RelationshipTypes.HIGH_FIVED, Direction.INCOMING));
            results.put(LOW_FIVES, post.getDegree(RelationshipTypes.LOW_FIVED, Direction.INCOMING));


            tx.success();
        }
        return Response.ok().entity(objectMapper.writeValueAsString(results)).build();
    }
}