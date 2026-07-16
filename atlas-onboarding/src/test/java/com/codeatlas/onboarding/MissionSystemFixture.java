package com.codeatlas.onboarding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A simplified, non-sensitive mixed Java + Ada "mission" system used by the
 * onboarding tests. It deliberately exercises every stage of the workflow:
 *
 * <ul>
 *   <li>a Java entry point ({@code MissionApplication.main}) and a REST endpoint
 *       ({@code POST /missions});</li>
 *   <li>a full Spring persistence path (controller → service → mapper →
 *       repository.save → {@code mission} table → response);</li>
 *   <li>a Java→Ada boundary with real evidence: a {@code native} (JNI) method
 *       {@code calculateRoute} whose name matches Ada {@code Calculate_Route}, a
 *       {@code ProcessBuilder} launch, and a messaging {@code publish} call;</li>
 *   <li>an Ada main procedure ({@code Mission_Main}), a processing package with
 *       package state ({@code Mission_State}) and console I/O, and a second Ada
 *       package ({@code Navigation});</li>
 *   <li>unresolved external dependencies (Java {@code TerrainProvider}, Ada
 *       {@code Telemetry}) that must surface honestly, never fabricated.</li>
 * </ul>
 *
 * No real weapon-system names or sensitive data are used.
 */
final class MissionSystemFixture {

    private MissionSystemFixture() {
    }

    static void write(Path repo) throws IOException {
        Path java = repo.resolve("java/com/example/mission");
        Path ada = repo.resolve("ada");
        Files.createDirectories(java);
        Files.createDirectories(ada);

        // Build files (drive build-system detection and reading-order stage 1).
        Files.writeString(repo.resolve("pom.xml"),
                "<project><groupId>com.example</groupId><artifactId>mission</artifactId></project>\n");
        Files.writeString(repo.resolve("mission.gpr"),
                "project Mission is\n   for Source_Dirs use (\"ada\");\nend Mission;\n");

        // ---- Java ----
        Files.writeString(java.resolve("MissionApplication.java"), """
                package com.example.mission;

                public class MissionApplication {
                    public static void main(String[] args) {
                        MissionRunner.run(args);
                    }
                }
                """);

        Files.writeString(java.resolve("MissionController.java"), """
                package com.example.mission;

                @RestController
                @RequestMapping("/missions")
                public class MissionController {

                    @Autowired
                    private MissionPlanningService missionPlanningService;

                    @PostMapping
                    public ResponseEntity<MissionResponse> createMission(@Valid @RequestBody MissionRequest request) {
                        return ResponseEntity.ok(missionPlanningService.plan(request));
                    }
                }
                """);

        Files.writeString(java.resolve("MissionPlanningService.java"), """
                package com.example.mission;

                @Service
                public class MissionPlanningService {

                    @Autowired
                    private MissionRepository missionRepository;
                    @Autowired
                    private MissionMapper missionMapper;
                    @Autowired
                    private AdaMissionAdapter adaMissionAdapter;
                    @Autowired
                    private TerrainProvider terrainProvider;
                    @Autowired
                    private MissionPublisher missionPublisher;

                    public MissionResponse plan(MissionRequest request) {
                        MissionEntity entity = missionMapper.toEntity(request);
                        missionRepository.save(entity);
                        terrainProvider.lookup(entity);
                        MissionResponse response = adaMissionAdapter.calculate(entity);
                        missionPublisher.publish(entity);
                        return response;
                    }
                }
                """);

        Files.writeString(java.resolve("AdaMissionAdapter.java"), """
                package com.example.mission;

                public class AdaMissionAdapter {

                    // JNI binding into the Ada mission-planning core (libmission).
                    public native long calculateRoute(long missionHandle);

                    public MissionResponse calculate(MissionEntity mission) {
                        long handle = mission.getId();
                        long routeId = calculateRoute(handle);
                        ProcessBuilder builder = new ProcessBuilder("mission_main");
                        return new MissionResponse(routeId);
                    }
                }
                """);

        Files.writeString(java.resolve("MissionMapper.java"), """
                package com.example.mission;

                public class MissionMapper {
                    MissionEntity toEntity(MissionRequest request) {
                        return new MissionEntity();
                    }
                    MissionResponse toResponse(MissionEntity entity) {
                        return new MissionResponse(0L);
                    }
                }
                """);

        Files.writeString(java.resolve("MissionRepository.java"), """
                package com.example.mission;

                public interface MissionRepository extends JpaRepository<MissionEntity, Long> {
                }
                """);

        Files.writeString(java.resolve("MissionEntity.java"), """
                package com.example.mission;

                @Entity
                @Table(name = "mission")
                public class MissionEntity {
                    @Id
                    private Long id;
                    private String target;
                    public Long getId() { return id; }
                }
                """);

        Files.writeString(java.resolve("MissionRequest.java"), """
                package com.example.mission;

                public class MissionRequest {
                    private String target;
                }
                """);

        Files.writeString(java.resolve("MissionResponse.java"), """
                package com.example.mission;

                public class MissionResponse {
                    private long routeId;
                    public MissionResponse(long routeId) { this.routeId = routeId; }
                }
                """);

        // An ordinary helper class — its methods must NOT be classified as entry points.
        Files.writeString(java.resolve("MissionRunner.java"), """
                package com.example.mission;

                public class MissionRunner {
                    static void run(String[] args) {
                        helper();
                    }
                    static void helper() {
                    }
                }
                """);

        // A test for the primary flow (drives the reading-order tests stage).
        Files.writeString(java.resolve("MissionServiceTest.java"), """
                package com.example.mission;

                public class MissionServiceTest {
                    void plansAMission() {
                    }
                }
                """);

        // ---- Ada ----
        Files.writeString(ada.resolve("navigation.ads"), """
                package Navigation is

                   Current_Heading : Natural := 0;

                   procedure Update_Heading (Value : Natural);

                end Navigation;
                """);

        Files.writeString(ada.resolve("navigation.adb"), """
                package body Navigation is

                   procedure Update_Heading (Value : Natural) is
                   begin
                      Current_Heading := Value;
                   end Update_Heading;

                end Navigation;
                """);

        Files.writeString(ada.resolve("mission_planning.ads"), """
                package Mission_Planning is

                   Mission_State : Natural := 0;

                   type Route_Type is record
                      Length : Natural;
                   end record;

                   procedure Calculate_Route;
                   function Transform (Value : Natural) return Route_Type;

                end Mission_Planning;
                """);

        Files.writeString(ada.resolve("mission_planning.adb"), """
                with Ada.Text_IO;
                with Navigation;
                with Telemetry;

                package body Mission_Planning is

                   function Transform (Value : Natural) return Route_Type is
                      Result : Route_Type;
                   begin
                      Result.Length := Value;
                      return Result;
                   end Transform;

                   procedure Calculate_Route is
                      Line_Text : String (1 .. 80);
                      Last      : Natural;
                      Route     : Route_Type;
                   begin
                      Ada.Text_IO.Get_Line (Line_Text, Last);
                      Route := Transform (Last);
                      Mission_State := Route.Length;
                      Navigation.Update_Heading (Mission_State);
                      Ada.Text_IO.Put_Line ("done");
                      Telemetry.Send (Mission_State);
                   end Calculate_Route;

                end Mission_Planning;
                """);

        Files.writeString(ada.resolve("mission_main.adb"), """
                with Mission_Planning;

                procedure Mission_Main is
                begin
                   Mission_Planning.Calculate_Route;
                end Mission_Main;
                """);
        // Note: TerrainProvider / MissionPublisher (Java) and Telemetry (Ada) are
        // deliberately absent — they must surface as honest unresolved dependencies.
    }
}
