These are specific instructions for the fork.

Generate new tables after update of OpenMapTiles schema:

```
./scripts/regenerate-openmaptiles.sh osmscout https://raw.githubusercontent.com/rinigus/openmaptiles/
git status
git add src/main/java/org/openmaptiles/generated/Tables.java src/main/java/org/openmaptiles/generated/OpenMapTilesSchema.java
git commit -m "Update generated tables"
```

Import map data:

```
./mvnw -DskipTests clean package
java -jar target/*with-deps.jar --force --download --area=estonia
```

