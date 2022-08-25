@echo off
if exist public\dist (
del /S /F /Q public\dist 
)

cd gc_front
call yarn build
cd ../public
mkdir dist
cd dist
xcopy /E /I ..\..\gc_front\dist
cd ../..
call sbt clean;dist
@echo on
