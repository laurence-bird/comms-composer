# Composer

[![CircleCI](https://circleci.com/gh/ovotech/comms-composer/tree/master.svg?style=svg)](https://circleci.com/gh/ovotech/comms-composer/tree/master)

Composes a comms payload given information about what template to use and some customer-specific data to insert into that template.

Only composes emails for now. At some point in the future it will support other channels, e.g. SMS, push.

## Running it locally

The following environment variables are required to run the service locally:
* KAFKA_HOSTS
  * Hosts in the format host1:9092,host2:9092

You can run the service directly with SBT via `sbt run` or by running the docker image:
* `sbt docker:publishLocal`
* `docker-compose up`

## Tests

`sbt test` to run the unit tests.

`sbt dockerComposeTest` to run the service tests. These involve running the service and its dependencies (Kafka, ZooKeeper and a fake S3 API) using docker-compose.

## Deployment

The service is deployed continuously to both the UAT and PRD (TBD) environments via the [CircleCI build](https://circleci.com/gh/ovotech/comms-composer) 
