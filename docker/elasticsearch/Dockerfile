ARG ELASTICSEARCH_VERSION=9.2.8
FROM docker.elastic.co/elasticsearch/elasticsearch:${ELASTICSEARCH_VERSION}

RUN bin/elasticsearch-plugin install --batch analysis-nori
