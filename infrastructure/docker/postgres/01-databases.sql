-- One database per service, so no service can read another's tables and each keeps its own Flyway
-- history. They all used to share one database and one flyway_schema_history table, so whichever
-- service migrated second would have failed validation against the first one's migrations.
-- Runs once, when the postgres volume is first initialised.
CREATE DATABASE devforge_auth;
CREATE DATABASE devforge_project;
CREATE DATABASE devforge_task;
CREATE DATABASE devforge_git;
CREATE DATABASE devforge_review;
CREATE DATABASE devforge_documentation;
CREATE DATABASE devforge_chat;
CREATE DATABASE devforge_analytics;
CREATE DATABASE devforge_notification;
