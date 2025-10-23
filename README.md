# Flow Simulator (Production-Ready)

Générateur d'événements JSON basé sur **JSON Schema** avec **résolution $ref**, jeux de données CSV, **sortie fichier NDJSON** et **Kafka**.

## Build & Run
```bash
mvn -q -DskipTests package
java -jar target/flow-sim-prod-1.0.0.jar
```

## API
POST `/api/simulate`
```json
{
  "schema": "order",
  "count": 10,
  "rate": 5,
  "destination": "BOTH",
  "topic": "orders"
}
```

## Config
`src/main/resources/application.yml` :
- `simulator.output.baseDir` : répertoire NDJSON
- `spring.kafka.bootstrap-servers` : brokers Kafka

## Schémas & datasets
- `resources/schemas/*.json`
- `resources/datasets/users.csv`, `products.csv`

## Qualité
- SOLID (résolveurs par type, stratégies x-source réutilisables)
- DRY (factory/registry centralisés)
- KISS (API claire)
- Performances : I/O bufferisé, génération légère. Pour haut débit, passer sur Reactor et batch Kafka.
