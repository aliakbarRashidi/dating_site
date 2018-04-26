package com.maxdemarzi.mentions;

import com.maxdemarzi.schema.Labels;
import com.maxdemarzi.schema.RelationshipTypes;
import com.maxdemarzi.users.Users;
import org.codehaus.jackson.map.ObjectMapper;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.maxdemarzi.Time.dateFormatter;
import static com.maxdemarzi.Time.utc;
import static com.maxdemarzi.posts.Posts.getAuthor;
import static com.maxdemarzi.schema.Properties.HASH;
import static com.maxdemarzi.schema.Properties.HIGH_FIVED;
import static com.maxdemarzi.schema.Properties.HIGH_FIVES;
import static com.maxdemarzi.schema.Properties.LOW_FIVED;
import static com.maxdemarzi.schema.Properties.LOW_FIVES;
import static com.maxdemarzi.schema.Properties.NAME;
import static com.maxdemarzi.schema.Properties.TIME;
import static com.maxdemarzi.schema.Properties.USERNAME;
import static java.util.Collections.reverseOrder;

@Path("/users/{username}/mentions")
public class Mentions {

    private static final Pattern mentionsPattern = Pattern.compile("@(\\S+)");

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @GET
    public Response getMentions(@PathParam("username") final String username,
                                @QueryParam("limit") @DefaultValue("25") final Integer limit,
                                @QueryParam("since") final Long since,
                                @QueryParam("username2") final String username2,
                                @Context GraphDatabaseService db) throws IOException {
        ArrayList<Map<String, Object>> results = new ArrayList<>();
        LocalDateTime dateTime;
        if (since == null) {
            dateTime = LocalDateTime.now(utc);
        } else {
            dateTime = LocalDateTime.ofEpochSecond(since, 0, ZoneOffset.UTC);
        }
        Long latest = dateTime.toEpochSecond(ZoneOffset.UTC);

        try (Transaction tx = db.beginTx()) {
            Node user = Users.findUser(username, db);
            Node user2 = null;
            HashSet<Node> highFived = new HashSet<>();
            HashSet<Node> lowFived = new HashSet<>();

            if (username2 != null) {
                user2 = Users.findUser(username2, db);
                for (Relationship r1 : user2.getRelationships(Direction.OUTGOING, RelationshipTypes.HIGH_FIVED)) {
                    highFived.add(r1.getEndNode());
                }
                for (Relationship r1 : user2.getRelationships(Direction.OUTGOING, RelationshipTypes.LOW_FIVED)) {
                    lowFived.add(r1.getEndNode());
                }

            }

            HashSet<Node> blocked = new HashSet<>();
            for (Relationship r1 : user.getRelationships(Direction.OUTGOING, RelationshipTypes.BLOCKS)) {
                blocked.add(r1.getEndNode());
            }
            LocalDateTime earliest = LocalDateTime.ofEpochSecond((Long)user.getProperty(TIME), 0, ZoneOffset.UTC);
            int count = 0;
            while (count < limit && (dateTime.isAfter(earliest))) {
                RelationshipType relType = RelationshipType.withName("MENTIONED_ON_" +
                        dateTime.format(dateFormatter));

                for (Relationship r1 : user.getRelationships(Direction.INCOMING, relType)) {
                    Node post = r1.getStartNode();
                    Map<String, Object> result = post.getAllProperties();
                    Long time = (Long)r1.getProperty("time");
                    if(time < latest) {
                        Node author = getAuthor(post, time);
                        if (!blocked.contains(author)) {
                            result.put(TIME, time);
                            result.put(USERNAME, author.getProperty(USERNAME));
                            result.put(NAME, author.getProperty(NAME));
                            result.put(HASH, author.getProperty(HASH));
                            result.put(HIGH_FIVED, highFived.contains(post));
                            result.put(LOW_FIVED, lowFived.contains(post));
                            result.put(HIGH_FIVES, post.getDegree(RelationshipTypes.HIGH_FIVED ,Direction.INCOMING));
                            result.put(LOW_FIVES, post.getDegree(RelationshipTypes.LOW_FIVED ,Direction.INCOMING));

                            results.add(result);
                            count++;
                        }
                    }
                }
                dateTime = dateTime.minusDays(1);
            }
            tx.success();
        }

        results.sort(Comparator.comparing(m -> (Long) m.get(TIME), reverseOrder()));

        return Response.ok().entity(objectMapper.writeValueAsString(results)).build();
    }

    public static void createMentions(Node post, HashMap<String, Object> input, LocalDateTime dateTime, GraphDatabaseService db) {
        Matcher mat = mentionsPattern.matcher(((String)input.get("status")).toLowerCase());

        for (Relationship r1 : post.getRelationships(Direction.OUTGOING, RelationshipType.withName("MENTIONED_ON_" +
                dateTime.format(dateFormatter)))) {
            r1.delete();
        }

        Set<Node> mentioned = new HashSet<>();
        while (mat.find()) {
            String username = mat.group(1);
            Node user = db.findNode(Labels.User, USERNAME, username);
            if (user != null && !mentioned.contains(user)) {
                Relationship r1 = post.createRelationshipTo(user, RelationshipType.withName("MENTIONED_ON_" +
                        dateTime.format(dateFormatter)));
                r1.setProperty(TIME, dateTime.toEpochSecond(ZoneOffset.UTC));
                mentioned.add(user);
            }
        }
    }
}
