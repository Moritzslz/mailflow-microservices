# mailflow-microservices

[![Build Status](https://img.shields.io/badge/build-puccess-green)](#)  

> A microservices suite for the Mailflow platform — decomposed services that manage components of the email workflow system.

## About

**mailflow-microservices** is the microservices architecture underpinning the Mailflow platform. Each functionality (e.g. sending email, routing logic, tracking, AI-completion) is separated into independent services, enabling scalability, independent deployment, and clearer boundaries.

This project is part of the broader Mailflow ecosystem (with **mailflow-api**, **mailflow-common**, **mailflow-ui**, etc.).

---

## Architecture & Services

A (non-exhaustive) breakdown of services:

| Service Name        | Responsibility / Domain                              |
|---------------------|--------------------------------------------------------|
| `mail-service`     | Accepts requests to send emails, interacts with SMTP / mail engine of each registered email account of each tenant |
| `llm-service`   | Uses RAG service to create responses to emails    |
| `rag-service`  | Is used to crawl, embed and store websites and other documents   |

You may have more or fewer services depending on your implementation.

<img width="1169" height="827" alt="MailFlow Architecture Diagram" src="https://github.com/user-attachments/assets/fe75553b-ff80-4fdb-9401-718c24cec605" />


### Authorisation Flow

<img width="1169" height="827" alt="MailFlow Authorisation Diagram" src="https://github.com/user-attachments/assets/2907b2f0-0a05-43e1-97dd-1cd945d36288" />

### Deployment Diagramm

<img width="1169" height="827" alt="MailFlow Deployment Diagram" src="https://github.com/user-attachments/assets/265ce520-5a7d-4f06-9386-0129d84e5854" />

---

## Features

- Decoupled microservices for each domain  
- REST APIs for each service  
- Asynchronous messaging / event-driven communication  
- Scalability, fault isolation, and independent deployability  
- Centralized logging and tracking  
- Configurable via environment variables, Docker, etc.  

---

## Getting Started

### Prerequisites

- Java 17+ (or the version your services use)  
- Gradle
- Docker / Docker Compose  
- Databases (PostgreSQL)
