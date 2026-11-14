This application is built in Java 27 / Spring Boot. This is the application backend. It is responsible for serving requests from the frontend as well as making requests to the Python data backend.

Generate OpenAPI spec for all controllers via Swagger, and expose for consumers. Do not implement any security configuration for now.

When making a change or adding a new file, always ensure there is basic class and method level Javadoc explaining the functionality and behaviour at a high-level.

When it comes time to add AI integration, ensure you are using Spring AI 2.0. Research the documentation before writing any code, as there are many differences from Spring AI 1.0. This is a later implementation step and work on this should not begin until the other design goals are achieved or you are specifically instructed to do so.

Refer to the project level AGENTS.md file in the parent directory of this folder for further context on design goals.

Sensitive configuration properties are hidden in an external file that agents are not permitted to access. If you need to add a property, inform the user in chat of what needs to be added.

The Python backend base URL is stored in property:

data.backend.url

The Python backend serves an OpenAPI spec mapped under /docs#/
