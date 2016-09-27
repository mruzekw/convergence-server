package com.convergencelabs.server.schema

import org.json4s.DefaultFormats
import org.json4s.Extraction
import org.json4s.jackson.JsonMethods

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.json4s.ShortTypeHints
import org.json4s.ext.EnumNameSerializer

object Test {

  case class Person(firstName: String, lastName: String, age: Int)
  case class Group(name: String, members: List[Person])

  val groupYaml = """name: my group
members:
  - firstName: Michael
    lastName: MacFadden
    age: 36
  - firstName: Jim
    lastName: james
    age: 23
"""

  val deltaYaml = """version: 0
description: Initial Schema Creation
changes:

########## User Class ##########

  - type: CreateClass
    name: User
    properties: 
      - name: username
        orientType: STRING
        constraints:
          mandatory: true 
          notNull: true

      - name: email
        orientType: STRING
        constraints:
          mandatory: true 
          notNull: true
          
      - name: firstName
        orientType: STRING
        constraints:
          mandatory: true 
          notNull: true

      - name: lastName
        orientType: STRING
        constraints:
          mandatory: true 
          notNull: true

  - type: CreateIndex
    className: User
    name: User.username
    indexType: UNIQUE
    properties: [username]

  - type: CreateIndex
    className: User
    name: User.email
    indexType: UNIQUE
    properties: [email]
    
########## UserCredential Class ##########

  - type: CreateClass
    name: UserCredential
    properties: 
      - name: user
        orientType: LINK
        classType: User
        constraints:
          mandatory: true 
          notNull: true

      - name: password
        orientType: STRING
        constraints:
          mandatory: true 
          notNull: true
          
  - type: CreateIndex
    className: UserCredential
    name: UserCredential.user
    indexType: UNIQUE
    properties: [user]
    
########## UserAuthToken Class ##########

  - type: CreateClass
    name: UserAuthToken
    properties: 
      - name: user
        orientType: LINK
        classType: User
        constraints:
          mandatory: true 
          notNull: true

      - name: token
        orientType: STRING
        constraints:
          mandatory: true 
          notNull: true
          
      - name: expireTime
        orientType: DATETIME
        constraints:
          mandatory: true 
          notNull: true
          
########## Domain Class ##########

  - type: CreateClass
    name: Domain
    properties: 
      - name: namespace
        orientType: STRING
        constraints:
          mandatory: true 
          notNull: true
          
      - name: domainId
        orientType: STRING
        constraints: 
          mandatory: true
          notNull: true
          
      - name: displayName
        orientType: STRING
        constraints: 
          mandatory: true
          
      - name: owner
        orientType: LINK
        classType: User
        constraints: 
          mandatory: true
          notNull: true
          
      - name: dbName
        orientType: STRING

      - name: dbUsername
        orientType: STRING
        
      - name: dbPassword
        orientType: STRING

      - name: status
        orientType: STRING
        constraints: 
          mandatory: true
          notNull: true
          
  - type: CreateIndex
    className: Domain
    name: Domain.dbName
    indexType: UNIQUE
    properties: [dbName]

  - type: CreateIndex
    className: Domain
    name: Domain.namespace_domainId
    indexType: UNIQUE_HASH_INDEX
    properties: [namespace, domainId]
    
########## Registration Class ##########

  - type: CreateClass
    name: Registration
    properties: 
      - name: email
        orientType: STRING
        constraints:
          mandatory: true 
          notNull: true
          
      - name: fname
        orientType: STRING
        constraints: 
          mandatory: true
          notNull: true
          
      - name: lname
        orientType: STRING
        constraints: 
          mandatory: true
          notNull: true
          
      - name: reason
        orientType: STRING
        constraints: 
          mandatory: true
          notNull: true
          
      - name: token
        orientType: STRING
        constraints: 
          mandatory: true
          notNull: true
          
      - name: approved
        orientType: BOOLEAN
        constraints: 
          mandatory: true
          notNull: true
          
  - type: CreateIndex
    className: Registration
    name: Registration.email
    indexType: UNIQUE
    properties: [email]

  - type: CreateIndex
    className: Registration
    name: Registration.email_token
    indexType: UNIQUE
    properties: [email, token]
    
"""
  
  

  val mapper = new ObjectMapper(new YAMLFactory())
  implicit val f = DefaultFormats.withTypeHintFieldName("type") +
    ShortTypeHints(List(classOf[CreateClass], classOf[AlterClass], classOf[DropClass], 
        classOf[AddProperty], classOf[AlterProperty], classOf[DropProperty],
        classOf[CreateIndex], classOf[DropIndex],
        classOf[CreateSequence], classOf[DropSequence],
        classOf[RunSQLCommand])) +
    new EnumNameSerializer(OrientType) +
    new EnumNameSerializer(IndexType) +
    new EnumNameSerializer(SequenceType)

  def main(args: Array[String]): Unit = {
    println(parseYaml[Group](groupYaml))
    println(parseYaml[Delta](deltaYaml))
  }

  def parseYaml[A](yaml: String)(implicit mf: Manifest[A]): A = {
    val jsonNode = mapper.readTree(yaml)
    val jValue = JsonMethods.fromJsonNode(jsonNode)
    println(jsonNode)
    Extraction.extract[A](jValue)
  }
}
