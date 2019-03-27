# Composer

[![CircleCI](https://circleci.com/gh/ovotech/comms-composer/tree/master.svg?style=svg)](https://circleci.com/gh/ovotech/comms-composer/tree/master)

Composes a comms payload given information about what template to use and some customer-specific data to insert into that template.

Currently composes email and SMS.  The intention is that as other flavours of comms are added (e.g. letters, push notifications), all the templating goes on here.

## Tests

`sbt test` to run the unit tests.

`sbt servicetest:test` to run the service tests. These involve running the service and its dependencies (Kafka, ZooKeeper and a fake S3 API) using docker-compose.

## Deployment

The service is deployed continuously to both the UAT and PRD (TBD) environments via the [CircleCI build](https://circleci.com/gh/ovotech/comms-composer) 

## Credstash

This service uses credstash for secret management, and this dependency is required if you want to publish the docker container for this project locally or to a remote server, or run the docker-compose tests. Information on how to install credstash can be found in the [Credstash readme](https://github.com/fugue/credstash)
