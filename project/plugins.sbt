resolvers += Resolver.bintrayIvyRepo("ovotech", "sbt-plugins")

addSbtPlugin("com.geirsson" % "sbt-scalafmt" % "1.5.1")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.8.0")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "2.1.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.15")
addSbtPlugin("com.mintbeans" % "sbt-ecr" % "0.12.0")
addSbtPlugin("com.github.tptodorov" % "sbt-cloudformation" % "0.7.3")
addSbtPlugin("com.github.cb372" % "sbt-explicit-dependencies" % "0.2.8")