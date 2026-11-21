# Impact_Ledger

**See the estimated impact of every franc you donate, backed by verified data and KPIs.**

Built at the IMC Trading × JetBrains hackathon (November 2026).

## The problem

Many people give little or nothing because they never find out what their donation changed. Impact reports arrive once a year, written by the charity itself, and rarely say what *your* gift did.

## Our solution

Impact Ledger shows donors the estimated impact of their giving with clear KPIs and real numbers, so they trust their donations and give more.

- **For companies:** one dashboard showing the impact of all their charitable giving, across every NGO they fund.
- **For employees:** an app to follow what their own donations are doing.
- **For NGOs:** an easy way to report results, with an AI agent collecting updates to keep their effort low.

### Example: an education NGO

| KPI | Example |
| --- | --- |
| Children who learned to read | reading at grade level after one year |
| Books published | in local languages |
| Teachers trained | per programme |
| Students who reached high school | from supported schools |

### Honest numbers

Every number is an **estimate**, labelled by how much you can trust it:

| Trust level | Source |
| --- | --- |
| Measured | Data collected directly (devices, partner measurements) |
| Reported | Published reports and financial statements, read by an AI agent |
| Self-reported | Updates submitted by NGO staff |

A donor's share of a project's results is shown as an estimate, never as an exact one-to-one link.

## First customer: IMC

IMC already gives at scale through its foundations and its annual Trading for Charity Day. Impact Ledger would let IMC show every employee what the firm's giving, and their own, achieved.

## Tech

Built with **Kotlin Multiplatform** in **IntelliJ IDEA**: one shared Kotlin core powering two apps.

| Part | Technology |
| --- | --- |
| Donor app | Android, Compose Multiplatform |
| Company and NGO app | Desktop (JVM), Compose Multiplatform |
| Shared logic | Kotlin `commonMain`: projects, donations, KPIs, trust levels, impact estimates |
| Networking | Ktor client, Ktor server |
| Data format | kotlinx.serialization (JSON) |

## Project structure (planned)

```
Impact_Ledger/
├── shared/          # commonMain: models, impact maths, API client
├── androidApp/      # donor app
├── desktopApp/      # company and NGO dashboard
└── server/          # Ktor backend (optional for the demo)
```

## Running the project

Instructions will be added once the Kotlin Multiplatform project is set up.

## Data

All NGO names and numbers in the demo are **simulated**.

## Team

[TEAM NAMES AND ROLES]
