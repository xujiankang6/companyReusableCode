CREATE TABLE "T8995372716592129"."customer_log" (
  "id" int8 NOT NULL,
  "name" text COLLATE "pg_catalog"."default",
  "create_by" text COLLATE "pg_catalog"."default",
  "update_by" text COLLATE "pg_catalog"."default",
  "create_time" int8,
  "update_time" int8,
  "record_type" text COLLATE "pg_catalog"."default" DEFAULT 'master'::text,
  "object_describe_id" int8 NOT NULL,
  "object_describe_name" text COLLATE "pg_catalog"."default" NOT NULL,
  "owner" text COLLATE "pg_catalog"."default",
  "belong_territory" int8,
  "tenant_id" text COLLATE "pg_catalog"."default",
  "version" int4 DEFAULT 0,
  "ext" jsonb,
  "external_id" text COLLATE "pg_catalog"."default",
  "client" text COLLATE "pg_catalog"."default",
  "last_sync" int8,
  "is_deleted" bool,
  "customer" int8,
  "field_api_name" text COLLATE "pg_catalog"."default",
  "field_name" text COLLATE "pg_catalog"."default",
  "new_data" text COLLATE "pg_catalog"."default",
  "old_data" text COLLATE "pg_catalog"."default",
  "content" text COLLATE "pg_catalog"."default",
  "log_type" text COLLATE "pg_catalog"."default",
  CONSTRAINT "pk_customer_log_id" PRIMARY KEY ("id")
)
INHERITS ("T8995372716592129"."tenant_data_record")
;

ALTER TABLE "T8995372716592129"."customer_log" 
  OWNER TO "crmpowerdbadmin";

CREATE INDEX "customer_log_create_by_index" ON "T8995372716592129"."customer_log" USING btree (
  "create_by" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);

CREATE INDEX "customer_log_is_deleted_index" ON "T8995372716592129"."customer_log" USING btree (
  "is_deleted" "pg_catalog"."bool_ops" ASC NULLS LAST
);

CREATE INDEX "customer_log_owner_index" ON "T8995372716592129"."customer_log" USING btree (
  "owner" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);

CREATE INDEX "customer_log_record_type_index" ON "T8995372716592129"."customer_log" USING btree (
  "record_type" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);

CREATE INDEX "customer_log_tenant_id_index" ON "T8995372716592129"."customer_log" USING btree (
  "tenant_id" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);

CREATE INDEX "customer_log_update_time_index" ON "T8995372716592129"."customer_log" USING btree (
  "update_time" "pg_catalog"."int8_ops" ASC NULLS LAST
);