package com.codeatlas.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A representative Ada application for the lineage tests, covering the addendum's
 * Ada flow: console input → procedure → transformation function → package state →
 * console output, plus honesty cases (a call into a withed-but-absent Telemetry
 * package, and cross-package qualified state access).
 */
final class AdaLineageFixtures {

    private AdaLineageFixtures() {
    }

    static void writeMissionApp(Path repo) throws IOException {
        Path src = repo.resolve("src");
        Files.createDirectories(src);

        Files.writeString(src.resolve("raw_types.ads"), """
                package Raw_Types is

                   type Raw_Data is record
                      Size : Natural;
                   end record;

                   function Parse (Text : String) return Raw_Data;

                end Raw_Types;
                """);

        Files.writeString(src.resolve("mission_data.ads"), """
                with Raw_Types;

                package Mission_Data is

                   Status : Natural := 0;

                   type Route_Type is record
                      Length    : Natural;
                      Waypoints : Natural;
                   end record;

                   procedure Load_Route;
                   procedure Publish_Route;
                   function Transform_Waypoints (Raw : Raw_Types.Raw_Data) return Route_Type;

                end Mission_Data;
                """);

        Files.writeString(src.resolve("mission_data.adb"), """
                with Ada.Text_IO;
                with Raw_Types;
                with Telemetry;

                package body Mission_Data is

                   Current_Route : Route_Type;

                   function Transform_Waypoints (Raw : Raw_Types.Raw_Data) return Route_Type is
                      Result : Route_Type;
                   begin
                      Result.Length := Raw.Size;
                      return Result;
                   end Transform_Waypoints;

                   function Route_Image (R : Route_Type) return String is
                   begin
                      return "route";
                   end Route_Image;

                   procedure Load_Route is
                      Line_Text : String (1 .. 80);
                      Last      : Natural;
                   begin
                      Ada.Text_IO.Get_Line (Line_Text, Last);
                      Current_Route := Transform_Waypoints (Raw_Types.Parse (Line_Text));
                      Status := Status + 1;
                   end Load_Route;

                   procedure Publish_Route is
                   begin
                      Ada.Text_IO.Put_Line (Route_Image (Current_Route));
                      Telemetry.Send (Current_Route);
                   end Publish_Route;

                end Mission_Data;
                """);

        Files.writeString(src.resolve("operations.ads"), """
                package Operations is

                   procedure Reset_Mission;

                end Operations;
                """);

        Files.writeString(src.resolve("operations.adb"), """
                with Mission_Data;

                package body Operations is

                   procedure Reset_Mission is
                   begin
                      Mission_Data.Status := 0;
                   end Reset_Mission;

                end Operations;
                """);
        // Note: Telemetry is deliberately NOT in the repository — the calls to
        // Telemetry.Send must surface as explicit unresolved lineage gaps.
    }
}
