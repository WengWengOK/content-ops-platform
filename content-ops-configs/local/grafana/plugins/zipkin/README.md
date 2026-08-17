# Grafana Zipkin data source

The Zipkin data source plugin lets you query and visualize traces from
[Zipkin](https://zipkin.io) — an open source, distributed tracing system — inside
Grafana. It ships built-in with Grafana, so most users don't need to install it
separately.

## Features

- **Query traces by ID** or interactively drill down through a **service → operation → trace** selector in [Explore](https://grafana.com/docs/grafana/latest/explore/).
- **Upload a JSON trace file** and visualize it without a running Zipkin instance.
- **Node graph** visualization of a trace's service topology.
- **Trace to logs** and **trace to metrics** links to pivot from a span to correlated logs (Loki, Splunk, Elasticsearch) or metrics (Prometheus).
- **Span filtering** in the trace timeline viewer.
- Links to traces from logs (via derived fields / data links) and from metrics (via exemplars).

## Documentation

User-facing documentation — configuration, querying, provisioning, and trace
correlation — lives at:

- [Zipkin data source documentation](https://grafana.com/docs/grafana/latest/datasources/zipkin/)

The source for that page is in [`docs/sources/_index.md`](docs/sources/_index.md).

## Installation

The Zipkin data source is included with Grafana by default — no installation
required. To add and configure an instance:

1. Go to **Connections → Data sources** in Grafana.
2. Click **Add new data source** and select **Zipkin**.
3. Set the **URL** of your Zipkin instance (for example, `http://localhost:9411`).
4. Click **Save & test**.

You can also provision the data source with YAML. See the
[provisioning example](https://grafana.com/docs/grafana/latest/datasources/zipkin/#provision-the-data-source)
in the documentation.

## Compatibility

This plugin requires Grafana `>=12.3.0`. See
[`src/plugin.json`](src/plugin.json) for the authoritative `grafanaDependency`.

## Contributing

Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for how to set
up your environment, build the plugin, run tests, and propose changes. For an
overview of the codebase architecture, see [DEVELOPMENT.md](DEVELOPMENT.md).

## License

This plugin is licensed under the [AGPL-3.0 License](LICENSE).
