rm -fr mysite

mvn -B archetype:generate \
 -D archetypeGroupId=com.cognifide.aem \
 -D archetypeArtifactId=aem-project-archetype-hybrid \
 -D archetypeVersion=25-SNAPSHOT \
 -D aemVersion=cloud \
 -D appTitle="My Site" \
 -D appId="mysite" \
 -D groupId="com.mysite" \
 -D frontendModule=general \
 -D includeExamples=n