# SARSA REST Bug Hunter

A simple Java Spring Boot REST API for SARSA reinforcement learning bug hunting experiments.

## Requirements

- Java 17+
- Maven 3.6+

## Running the API

```bash
./mvnw spring-boot:run
```

Or with Maven installed:

```bash
mvn spring-boot:run
```

The API runs on **http://localhost:8080**

## API Endpoints

Base URL: `/api/items`

| Method  | Endpoint          | Description           | Request Body |
|---------|-------------------|-----------------------|--------------|
| GET     | `/api/items`      | Get all items         | -            |
| GET     | `/api/items/{id}` | Get single item       | -            |
| POST    | `/api/items`      | Create new item       | JSON         |
| PUT     | `/api/items/{id}` | Full update           | JSON         |
| PATCH   | `/api/items/{id}` | Partial update        | JSON         |
| DELETE  | `/api/items/{id}` | Delete single item    | -            |
| DELETE  | `/api/items`      | Delete all items      | -            |
| HEAD    | `/api/items/{id}` | Check if item exists  | -            |
| OPTIONS | `/api/items/{id}` | Get allowed methods   | -            |

## Item Model

```json
{
  "id": 1,
  "name": "string",
  "description": "string",
  "quantity": 0
}
```

## Example Requests

### Create an item (POST)

```bash
curl -X POST http://localhost:8080/api/items \
  -H "Content-Type: application/json" \
  -d '{"name":"Widget","description":"A test widget","quantity":5}'
```

### Get all items (GET)

```bash
curl http://localhost:8080/api/items
```

### Get single item (GET)

```bash
curl http://localhost:8080/api/items/1
```

### Full update (PUT)

```bash
curl -X PUT http://localhost:8080/api/items/1 \
  -H "Content-Type: application/json" \
  -d '{"name":"Updated Widget","description":"Updated description","quantity":10}'
```

### Partial update (PATCH)

```bash
curl -X PATCH http://localhost:8080/api/items/1 \
  -H "Content-Type: application/json" \
  -d '{"quantity":20}'
```

### Delete an item (DELETE)

```bash
curl -X DELETE http://localhost:8080/api/items/1
```

### Check if item exists (HEAD)

```bash
curl -I -X HEAD http://localhost:8080/api/items/1
```

### Get allowed methods (OPTIONS)

```bash
curl -X OPTIONS http://localhost:8080/api/items/1
```

## Response Codes

| Code | Description |
|------|-------------|
| 200  | Success |
| 201  | Created |
| 204  | No Content (successful delete) |
| 404  | Not Found |

## Notes

- Uses in-memory storage (ConcurrentHashMap) - data resets on restart
- No authentication required
- CORS not configured (add if needed for browser requests)
