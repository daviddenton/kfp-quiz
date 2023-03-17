package com.techtev.quiz

import com.techtev.quiz.db.QuizTable
import com.techtev.quiz.db.UserTable
import com.techtev.quiz.db.quizPersistence
import com.techtev.quiz.db.userPersistence
import org.http4k.contract.bind
import org.http4k.contract.contract
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.v3.OpenApi3
import org.http4k.contract.ui.swaggerUi
import org.http4k.core.*
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.filter.ServerFilters
import org.http4k.format.Jackson
import org.http4k.format.KotlinxSerialization
import org.http4k.lens.RequestContextKey
import org.http4k.lens.string
import org.http4k.routing.routes
import org.http4k.server.Jetty
import org.http4k.server.asServer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

fun main() {
    Database.connect("jdbc:h2:file:./quizdb", "org.h2.Driver", "sa", "password")
    transaction {
        // print sql to std-out
        addLogger(StdOutSqlLogger)

        SchemaUtils.createMissingTablesAndColumns(QuizTable)
        SchemaUtils.create(UserTable)
    }
    val userRepository = userRepository(userPersistence(UserTable))
    val userService = userService(userRepository)
    val userApi = contract {
        renderer = OpenApi3(ApiInfo("User service", "v1.0"), Jackson)
        descriptionPath = "user"
        routes += userRoutes(userService)
    }
    val contexts = RequestContexts()
    val credentialsKey = RequestContextKey.required<Credentials>(contexts)
    val quizApi = contract {
        renderer = OpenApi3(ApiInfo("Quiz service", "v1.0"), Jackson)
        descriptionPath = "quiz"
        routes += quizRoutes(
            quizService(quizRepository(quizPersistence(QuizTable)), userRepository),
            credentialsKey
        )
    }.withFilter(ServerFilters.BasicAuth("kfp-quiz", credentialsKey) { credentials ->
        userService.isValidUser(credentials.user, credentials.password)
            .fold({ null }, { credentials })
    })
    val ui = swaggerUi(
        descriptionRoute = Uri.of("spec"),
        title = "Quiz service",
        displayOperationId = true
    )
    ServerFilters.InitialiseRequestContext(contexts)
        .then(
            ServerFilters.CatchLensFailure { failure ->
                Response(BAD_REQUEST).with(
                    Body.string(ContentType.APPLICATION_JSON)
                        .toLens() of "{\"message\":${failure.cause?.message.orEmpty()}"
                )
            })
        .then(routes(quizApi, userApi, ui))
        .asServer(Jetty(port = 8080))
        .start()
}
