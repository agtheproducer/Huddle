package com.impactledger.app

/** Simulated demo data. NGO and project names are fictional. */
object DemoData {
    val projects = listOf(
        Project(
            id = "read-together",
            name = "Read Together",
            ngo = "Bright Pages Foundation",
            country = "Tanzania",
            cause = "Literacy",
            fundedChf = 120_000.0,
            promise = "1,500 children reading at grade level by the end of the school year",
            kpis = listOf(
                Kpi("Children who learned to read", 1_240.0, "children", TrustLevel.REPORTED),
                Kpi("Books published in local languages", 3_400.0, "books", TrustLevel.MEASURED),
                Kpi("Teachers trained", 86.0, "teachers", TrustLevel.SELF_REPORTED),
            ),
            updates = listOf(
                "Reading test results for 14 schools published",
                "1,200 new books delivered to Arusha region",
            ),
        ),
        Project(
            id = "path-to-high-school",
            name = "Path to High School",
            ngo = "Open Doors Education",
            country = "India",
            cause = "Secondary education",
            fundedChf = 80_000.0,
            promise = "350 students moving on to high school this year",
            kpis = listOf(
                Kpi("Students who reached high school", 312.0, "students", TrustLevel.REPORTED),
                Kpi("Girls enrolled in the programme", 190.0, "girls", TrustLevel.REPORTED),
                Kpi("Mentors active", 40.0, "mentors", TrustLevel.SELF_REPORTED),
            ),
            updates = listOf(
                "Enrolment confirmed by 9 partner high schools",
                "Mentoring sessions doubled since last term",
            ),
        ),
        Project(
            id = "library-every-school",
            name = "A Library in Every School",
            ngo = "Story Roots",
            country = "South Africa",
            cause = "School libraries",
            fundedChf = 45_000.0,
            promise = "20 school libraries open this year",
            kpis = listOf(
                Kpi("Libraries opened", 18.0, "libraries", TrustLevel.MEASURED),
                Kpi("Books distributed", 9_000.0, "books", TrustLevel.MEASURED),
                Kpi("Children borrowing books weekly", 2_100.0, "children", TrustLevel.SELF_REPORTED),
            ),
            updates = listOf(
                "Library 18 opened, GPS photo matches the planned school",
                "Two libraries delayed by building works",
            ),
        ),
    )

    /** The signed-in employee's donations so far, in CHF per project. */
    val startingDonations = mapOf(
        "read-together" to 1_000.0,
        "path-to-high-school" to 500.0,
    )
}
