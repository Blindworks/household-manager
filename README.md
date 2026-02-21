# Household Manager

Household Manager is a full-stack application for monitoring and managing household utility usage and environmental data. It supports manual and automatic meter readings (electricity, gas, water), real-time power consumption, and air quality monitoring — all presented in an interactive dashboard with analytics and cost estimation.

---

## Key Features

- Manual and CSV import of meter readings  
- Automatic real-time electricity consumption monitoring  
- Automatic air quality tracking (e.g., CO₂, VOC, particulate matter)  
- Trend visualization (daily, weekly, monthly)  
- Cost estimation based on tariff data  
- History browsing and search  
- Sensor agents for periodic automation  
- Docker support for easy deployment  
- REST API backend with Angular frontend  

---

## Repository Structure

/
├── backend/                # Spring Boot API  
├── frontend/               # Angular web UI  
├── agents/                 # Automatic sensor agents  
├── scripts/                # Utility and automation scripts  
├── docker-compose.yml      # Docker configuration  
├── SETUP.md                # Setup and configuration instructions  
├── TESTING.md              # Testing guidelines  
├── AGENTS.md               # Sensor agent specs  
├── CLAUDE.md               # Notes on AI/automation integrations  

---

## Real-Time Sensor Integration

Household Manager includes support for automatic data collection via dedicated agents:

- Electricity Consumption Agent — polls connected smart meters or measurement hardware to fetch current power usage.  
- Air Quality Agent — reads data from environmental sensors (CO₂, VOC, PM sensors) at scheduled intervals.  

Agents send data to the backend REST API and can be configured independently. See AGENTS.md for details and supported hardware.

---

## Backend (Spring Boot)

The backend service exposes a REST API for data ingestion, validation, storage, and analytics.

### Requirements

- Java 17 or newer  
- Maven  
- PostgreSQL (or another supported SQL database)  

### Run Locally

cd backend  
mvn clean install  
java -jar target/*.jar  

The service starts by default on:  
http://localhost:8080  

Database connection and other environment settings are configured via application.yml or environment variables.

---

## Frontend (Angular)

The frontend is an Angular Single-Page Application (SPA) providing:

- Dashboards for consumption and air quality data  
- Forms for manual meter input  
- Trend charts and history views  

### Requirements

- Node.js  
- npm or yarn  

### Run Locally

cd frontend  
npm install  
npm start  

Open your browser at:  
http://localhost:4200  

---

## Docker Compose

If you have Docker and Docker Compose installed, start the full stack (backend, frontend, database, and agents) with:

docker compose up --build  

This will launch all services together for easy local development or testing.

---

## Testing

Run backend and frontend tests as follows:

Backend Tests:

cd backend  
mvn test  

Frontend Tests:

cd frontend  
npm test  

More testing guidelines are available in TESTING.md.

---

## Documentation

- SETUP.md — Detailed setup and configuration  
- TESTING.md — How to run and write tests  
- AGENTS.md — Instructions for sensor agents  
- CLAUDE.md — Notes on AI-based automation features  

---

## Contributing

Contributions, issue reports, and feature requests are welcome. Please use GitHub Issues and Pull Requests for collaboration.

---

## License

This project currently does not contain a license. Consider adding an open-source license (e.g., MIT or Apache-2.0) to clarify usage and redistribution terms.

---

## Acknowledgements

Household Manager is built with Spring Boot, Angular, and dedicated automation agents to provide a practical solution for real-time utility tracking and environmental monitoring.
